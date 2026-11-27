package pepegahs.p1.kodr.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

final class AvatarImages {
    private static final String PNG_PREFIX = "data:image/png;base64,";
    private static final int SIDE = 256;

    private AvatarImages() {
    }

    static String normalize(String source) {
        if (source == null) {
            return null;
        }
        if (!source.startsWith(PNG_PREFIX)) {
            throw invalidImage();
        }
        try {
            byte[] data = Base64.getDecoder().decode(source.substring(PNG_PREFIX.length()));
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) {
                    throw invalidImage();
                }
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    if (!reader.getFormatName().equalsIgnoreCase("PNG") ||
                            reader.getWidth(0) != SIDE || reader.getHeight(0) != SIDE) {
                        throw invalidImage();
                    }
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(reader.read(0), "PNG", output)) {
                        throw invalidImage();
                    }
                    return PNG_PREFIX + Base64.getEncoder().encodeToString(output.toByteArray());
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException error) {
            throw invalidImage();
        }
    }

    private static ResponseStatusException invalidImage() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Изображение аватара должно быть PNG размером 256 × 256");
    }
}
