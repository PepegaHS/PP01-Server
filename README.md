# Kodr Server

Серверная часть платформы для изучения Python, Java, C, C++, C# и PHP. API Gateway проверяет JWT Spring Authorization Server и маршрутизирует запросы через Eureka. Микросервисы также проверяют подпись, издателя и аудиторию JWT.

## Запуск

Для локального запуска нужны Docker и Docker Compose. Профиль `judge0` запускает Judge0 CE 1.13.1 вместе с его PostgreSQL и Redis. Для локального Judge0 нужен поддерживаемый Linux-хост с cgroup v1. [Требования Judge0 CE](https://github.com/judge0/judge0/releases/tag/v1.13.1)

```sh
docker compose --profile judge0 up --build -d
```

В Docker Desktop с cgroup v2 контейнеры Judge0 CE 1.13.1 запускаются, но воркер возвращает `Internal Error` при выполнении кода. Для такого окружения запустите Kodr без профиля `judge0` и укажите доступный из контейнера URL совместимого Judge0 CE. Для защищённого API можно задать `JUDGE0_TOKEN` и `JUDGE0_AUTH_HEADER`.

```sh
JUDGE0_URL=https://judge0.example.com docker compose up --build -d
```

При запуске Compose контейнер `postgres-init` создаёт недостающие базы `kodr_user`, `kodr_course`, `kodr_task`, `kodr_submission`, `kodr_progress` и `kodr_auth` с отдельными владельцами. Каждый сервис с собственной базой применяет свои миграции Flyway из `src/main/resources/db/migration`. Auth Service хранит аккаунты, хэши паролей, ключ подписи JWT и OAuth2-авторизации в PostgreSQL. В профиле `judge0` контейнер Judge0 инициализирует отдельную базу `judge0`. Execution Service хранит результаты ручных запусков в Redis 30 минут.

Конфигурация Spring, скрипт создания баз и конфигурация локального Judge0 включены в Docker-образы. После изменения этих файлов пересоберите образы через `docker compose up --build -d` с тем же профилем запуска. После прерванной инициализации `postgres-init` создаст недостающие базы в существующем томе.

| Компонент | Адрес |
| --- | --- |
| API Gateway и Swagger UI | <http://localhost:8080/swagger-ui.html> |
| Eureka | <http://localhost:8761> |
| RabbitMQ Management | <http://localhost:15672> |
| Jaeger | <http://localhost:16686> |

## Регистрация и получение токена

Для нового аккаунта откройте `auth-service` в Swagger UI и вызовите `POST /api/auth/register` с полями `username`, `email` и `password`. Имя пользователя и почта уникальны без учёта регистра. Пароль должен содержать от 12 до 128 символов.

1. В Swagger UI выберите `auth-service` и откройте `POST /api/auth/login`.
2. Введите `username` и `password`. В ответе `200 OK` поле `access_token` содержит JWT со сроком действия 10 минут. При неверных учётных данных API возвращает `401 Unauthorized`.
3. Выберите нужный сервис, нажмите **Authorize**, вставьте значение `access_token` в схему **kodrBearer** и подтвердите. Чтобы заполнить свой профиль, вызовите `PUT /api/users/me`. Для других HTTP-клиентов используйте заголовок `Authorization: Bearer <access_token>`.

### Тестовый администратор

При запуске с настройками Compose по умолчанию Auth Service создаёт эту учётную запись, если в базе ещё нет администратора.

| Логин | Почта | Пароль для локальной проверки |
| --- | --- | --- |
| `admin` | `admin@kodr.native` | `kodr-dev-admin` |

Вызовите `POST /api/auth/login` в Swagger с логином `admin` и вставьте полученный JWT в **Authorize → kodrBearer**. JWT этого аккаунта даёт доступ к `GET /actuator/info` на Gateway.

При первом создании администратора можно задать `AUTH_ADMIN_USERNAME`, `AUTH_ADMIN_EMAIL` и `AUTH_ADMIN_PASSWORD`. Для `AUTH_ADMIN_EMAIL` подходит любой корректный уникальный адрес. Пустой `AUTH_ADMIN_PASSWORD` отключает автоматическое создание администратора.

Для локальной разработки также доступны аккаунты `student` / `kodr-dev-student` и `teacher` / `kodr-dev-teacher`. Переменная `AUTH_DEMO_USERS=false` отключает их создание. Регистрация через API создаёт только ученика. RabbitMQ использует локальные данные `kodr` / `kodr-dev-rabbit`.

Публичный адрес Gateway и издателя JWT задаётся через `KODR_PUBLIC_URL`. Submission Service получает служебный токен по Client Credentials через внутренний `POST /oauth2/token` Auth Service.

### Перенос локальных аккаунтов из Keycloak

Том PostgreSQL сохраняет старую базу `kodr_keycloak`. Для переноса существующих пользователей впервые запустите новую версию с отключённым созданием демонстрационных аккаунтов и администратора. После запуска Auth Service выполните скрипт. Если старая база находится на другом сервере, сначала восстановите её из резервной копии в PostgreSQL Kodr.

```sh
AUTH_DEMO_USERS=false AUTH_ADMIN_PASSWORD= docker compose up --build -d --wait --remove-orphans
python3 infra/postgres/import-keycloak-accounts.py
```

Скрипт переносит UUID, почту, имя пользователя, роль и поддерживаемые хэши Argon2id из realm `kodr`. UUID сохраняют связь с профилями, курсами и решениями. Аккаунты без локального поддерживаемого хэша, со вторым фактором или обязательным действием требуют ручного переноса.

## Сценарий работы

| Сервис | Маршрут | Что доступно |
| --- | --- | --- |
| Auth | `/api/auth/register`, `/api/auth/login` | Регистрация, вход и выдача токенов |
| User | `/api/users` | Создание и просмотр профилей, аватарки и настройки |
| Course | `/api/courses` | Курсы, модули, уроки и публикация |
| Task | `/api/tasks` | Условия, шаблоны кода, языки, ограничения и скрытые тесты |
| Submission | `/api/submissions` | Отправка решения, история и итог проверки |
| Execution | `/api/execution` | Свободный запуск с вводом и получение результата |
| Progress | `/api/progress` | Пройденные уроки и решённые задания по курсу |

Для ручного запуска программы откройте `POST /api/execution/runs` в Swagger после входа и отправьте тело запроса.

```json
{"language":"PYTHON","sourceCode":"print(input())","stdin":"Привет"}
```

Для проверки задания преподаватель создаёт и публикует курс и задание через Swagger. Ученик передаёт `taskId`, `language` и `sourceCode` в `POST /api/submissions`. Запрос возвращает `202` и идентификатор решения. `GET /api/submissions/{id}` возвращает статус `QUEUED` или `COMPLETED` и итоговый вердикт. После `ACCEPTED` задание появляется в прогрессе курса. Учёт пройденного урока выполняется через `PUT /api/progress/courses/{courseId}/lessons/{lessonId}`.

## Проверка и наблюдаемость

```sh
./mvnw verify
docker compose config --quiet
docker compose ps
docker compose logs -f submission-service execution-service
```

Maven-сборке нужен JDK 21 или новее. Конфигурация сервисов хранится в `config/` и загружается через Spring Cloud Config. Actuator публикует `/actuator/health`, `/actuator/info` и `/actuator/prometheus`. На Gateway и бизнес-сервисах для `info` и `prometheus` нужна роль `admin`. В логах присутствуют идентификаторы трасс и спанов. Jaeger принимает OTLP-трассы HTTP-вызовов, обращений к Judge0 и событий RabbitMQ. Gateway и клиент Judge0 используют Circuit Breaker.

Submission Service сохраняет решение и запись transactional outbox одной транзакцией. Он публикует сообщение со снимком тестов и ждёт подтверждения RabbitMQ. Execution Service выполняет тесты через Judge0, а Progress Service обновляет прогресс после подтверждённого решения. Повторная доставка итогового вердикта или события `ACCEPTED` не меняет уже проверенное решение и не дублирует прогресс. Сообщения с исчерпанными попытками обработки доступны в очередях с суффиксом `.dead`.
