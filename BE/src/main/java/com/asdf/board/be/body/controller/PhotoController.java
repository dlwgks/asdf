package com.asdf.board.be.body.controller;

import com.asdf.board.be.body.entity.Photo;
import com.asdf.board.be.body.repository.PhotoRepository;
import com.asdf.board.be.community.service.PostImageStorage;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.asdf.board.be.global.time.KstTime;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "body", description = "체형 분석")
@RestController
@RequestMapping("/api/photos")
@RequiredArgsConstructor
@Transactional
public class PhotoController {

    private final PhotoRepository photoRepository;
    private final UserRepository userRepository;
    private final PostImageStorage storage;

    @Value("${vitality.base-url:http://localhost:8080}")
    private String baseUrl;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(
            @RequestPart("image") MultipartFile file,
            @RequestPart(value = "type", required = false) String type,
            @RequestPart(value = "side", required = false) String side,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        User user = userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));

        Photo.Type photoType;
        Photo.Side photoSide;
        try {
            photoType = type != null ? Photo.Type.valueOf(type) : Photo.Type.current;
            photoSide = side != null ? Photo.Side.valueOf(side) : Photo.Side.front;
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }

        try {
            if (!isJpegOrPng(file)) throw new ApiException(ErrorCode.INVALID_IMAGE);
            String key = storage.store(file);
            Photo photo = Photo.builder()
                    .user(user)
                    .type(photoType)
                    .side(photoSide)
                    .storageKey(key)
                    .contentType(isPng(file) ? "image/png" : "image/jpeg")
                    .sizeBytes((int) file.getSize())
                    .build();
            photo = photoRepository.save(photo);
            return photoToMap(photo);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = Long.valueOf(jwt.getSubject());
        var pageable = PageRequest.of(page - 1, pageSize);
        var result = photoRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pageable);
        return Map.of(
                "items", result.getContent().stream().map(this::photoToMap).toList(),
                "page", page,
                "total", result.getTotalElements()
        );
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        Photo photo = photoRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!photo.getUser().getId().equals(Long.valueOf(jwt.getSubject()))) throw new ApiException(ErrorCode.FORBIDDEN);
        return photoToMap(photo);
    }

    private Map<String, Object> photoToMap(Photo p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("photo_id", p.getId());
        m.put("type", p.getType().name());
        m.put("side", p.getSide().name());
        m.put("url", baseUrl + "/uploads/" + p.getStorageKey());
        m.put("url_expires_at", "2099-12-31T23:59:59");
        m.put("created_at", KstTime.iso(p.getCreatedAt()));
        return m;
    }

    private static byte[] head(MultipartFile file) {
        try (var in = file.getInputStream()) {
            return in.readNBytes(8);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static boolean isPng(MultipartFile file) {
        byte[] h = head(file);
        return h.length >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
    }

    private static boolean isJpegOrPng(MultipartFile file) {
        byte[] h = head(file);
        boolean jpeg = h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF;
        return jpeg || isPng(file);
    }
}
