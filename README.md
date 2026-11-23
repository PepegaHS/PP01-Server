# Kodr Server

Серверная часть платформы для изучения Python, Java, C, C++, C# и PHP. API Gateway проверяет JWT Keycloak и маршрутизирует запросы через Eureka. Микросервисы также проверяют подпись, издателя и аудиторию JWT.

## Запуск

Для локального запуска нужны Docker и Docker Compose. Профиль `judge0` запускает Judge0 CE 1.13.1 вместе с его PostgreSQL и Redis. Для локального Judge0 нужен поддерживаемый Linux-хост с cgroup v1. [Требования Judge0 CE](https://github.com/judge0/judge0/releases/tag/v1.13.1)

```sh
docker compose --profile judge0 up --build -d
```

Если уже есть совместимый сервер Judge0 CE, укажите доступный из контейнера URL. Для защищённого API можно задать `JUDGE0_TOKEN` и `JUDGE0_AUTH_HEADER`.

```sh
JUDGE0_URL=https://judge0.example.com docker compose up --build -d
```

При первом запуске PostgreSQL создаёт базы `kodr_user`, `kodr_course`, `kodr_task`, `kodr_submission`, `kodr_progress` и `kodr_keycloak` с отдельными владельцами. Каждый из пяти сервисов данных применяет свои миграции Flyway из `src/main/resources/db/migration`. Keycloak выполняет собственные миграции и импортирует realm `kodr`. В профиле `judge0` контейнер Judge0 инициализирует отдельную базу `judge0`. Execution Service хранит результаты ручных запусков в Redis 30 минут.

| Компонент | Адрес |
| --- | --- |
| API Gateway и Swagger UI | <http://localhost:8080/swagger-ui.html> |
| Keycloak и управление аккаунтом | <http://localhost:8180/realms/kodr/account/> |
| Eureka | <http://localhost:8761> |
| RabbitMQ Management | <http://localhost:15672> |
| Jaeger | <http://localhost:16686> |

Импорт realm содержит локальные учётные записи `student` / `kodr-dev-student` и `teacher` / `kodr-dev-teacher`. Администратор Keycloak по умолчанию использует `admin` / `kodr-dev-admin`, RabbitMQ использует `kodr` / `kodr-dev-rabbit`. Значения предназначены для локального окружения. Для браузерного клиента `kodr-web` включён Authorization Code с PKCE. Новые зарегистрированные пользователи получают роль `student`. Роль `teacher` выдаётся администратором Keycloak.

## Сценарий работы

| Сервис | Маршрут | Что доступно |
| --- | --- | --- |
| User | `/api/users` | Создание и просмотр профилей, аватарки и настройки |
| Course | `/api/courses` | Курсы, модули, уроки и публикация |
| Task | `/api/tasks` | Условия, шаблоны кода, языки, ограничения и скрытые тесты |
| Submission | `/api/submissions` | Отправка решения, история и итог проверки |
| Execution | `/api/execution` | Свободный запуск с вводом и получение результата |
| Progress | `/api/progress` | Пройденные уроки и решённые задания по курсу |

Получите токен локального демонстрационного ученика (пример использует `jq`) и запустите программу. Этот токен можно также вставить в Swagger UI через **Authorize**.

```sh
TOKEN=$(curl -fsS http://localhost:8180/realms/kodr/protocol/openid-connect/token \
  --data-urlencode grant_type=password \
  --data-urlencode client_id=kodr-cli \
  --data-urlencode username=student \
  --data-urlencode password=kodr-dev-student | jq -er .access_token)

curl -fsS http://localhost:8080/api/execution/runs \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"language":"PYTHON","sourceCode":"print(input())","stdin":"Привет"}'
```

Для проверки задания преподаватель создаёт и публикует курс и задание через Swagger. Ученик передаёт `taskId`, `language` и `sourceCode` в `POST /api/submissions`. Запрос возвращает `202` и идентификатор решения. `GET /api/submissions/{id}` возвращает статус `QUEUED` или `COMPLETED` и итоговый вердикт. После `ACCEPTED` задание появляется в прогрессе курса. Учёт пройденного урока выполняется через `PUT /api/progress/courses/{courseId}/lessons/{lessonId}`.

## Проверка и наблюдаемость

```sh
./mvnw verify
docker compose config --quiet
docker compose ps
docker compose logs -f submission-service execution-service
```

Maven-сборке нужен JDK 21 или новее. Конфигурация сервисов хранится в `config/` и загружается через Spring Cloud Config. Actuator публикует `/actuator/health`, `/actuator/info` и `/actuator/prometheus`. На Gateway и бизнес-сервисах для `info` и `prometheus` нужна роль `admin` realm `kodr`. В логах присутствуют идентификаторы трасс и спанов. Jaeger принимает OTLP-трассы HTTP-вызовов, обращений к Judge0 и событий RabbitMQ. Gateway и клиент Judge0 используют Circuit Breaker.

Submission Service сохраняет решение и запись transactional outbox одной транзакцией. Он публикует сообщение со снимком тестов и ждёт подтверждения RabbitMQ. Execution Service выполняет тесты через Judge0, а Progress Service обновляет прогресс после подтверждённого решения. Повторная доставка итогового вердикта или события `ACCEPTED` не меняет уже проверенное решение и не дублирует прогресс. Сообщения с исчерпанными попытками обработки доступны в очередях с суффиксом `.dead`.
