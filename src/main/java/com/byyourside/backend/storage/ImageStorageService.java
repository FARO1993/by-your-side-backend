package com.byyourside.backend.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

public interface ImageStorageService {
    String uploadUserAvatar(UUID userId, MultipartFile file) throws IOException;
}