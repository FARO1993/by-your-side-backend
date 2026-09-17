package com.byyourside.backend.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CloudinaryImageStorageService implements ImageStorageService {

    private final Cloudinary cloudinary;

    @Override
    public String uploadUserAvatar(UUID userId, MultipartFile file) throws IOException {
        // public_id fijo por usuario: cada subida nueva sobreescribe el
        // avatar anterior en Cloudinary en vez de acumular archivos huerfanos.
        Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                "folder", "avatars",
                "public_id", userId.toString(),
                "overwrite", true,
                "resource_type", "image",
                "transformation", ObjectUtils.asMap(
                        "width", 256,
                        "height", 256,
                        "crop", "fill",
                        "gravity", "face"
                )
        ));

        return (String) result.get("secure_url");
    }
}