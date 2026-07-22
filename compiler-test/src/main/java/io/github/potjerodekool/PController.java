package io.github.potjerodekool;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PController {

    public void uploadImage(final UUID id,
                            final MultipartFile image) throws IOException {
        final var bytes = (byte[]) image.getBytes();
        this.storeImage(bytes);
    }

    private void storeImage(final byte[] bytes) {

    }

    private void count() {
        final var pet = new Pet();
        final var map = (Map<String, Integer>) new HashMap<String, Integer>();
        final var status = pet.getStatus().name();
        final var count = map.getOrDefault(status, 0) + 1;
        map.put(status, count);
    }
}
