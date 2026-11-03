package com.asdf.board.be.community.controller;

import com.asdf.board.be.community.dto.*;
import com.asdf.board.be.community.service.CommunityService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Tag(name = "community", description = "커뮤니티")
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final CommunityService communityService;

    @GetMapping
    public PageResult<PostResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long userId = jwt != null ? Long.valueOf(jwt.getSubject()) : null;
        if (userId != null) return communityService.getPosts(category, page, pageSize, userId);
        return communityService.getPosts(category, page, pageSize);
    }

    @GetMapping("/{id}")
    public PostResponse get(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return communityService.getPost(id, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PostResponse create(
            @RequestPart("category") String category,
            @RequestPart("title") String title,
            @RequestPart("content") String content,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return communityService.createPost(
                new PostRequest(category, title, content, null),
                images,
                Long.valueOf(jwt.getSubject())
        );
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PostResponse update(
            @PathVariable Long id,
            @RequestPart("category") String category,
            @RequestPart("title") String title,
            @RequestPart("content") String content,
            @RequestPart(value = "remove_image_ids", required = false) List<String> removeImageIds,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @AuthenticationPrincipal Jwt jwt
    ) {
        List<Long> removeIds = removeImageIds == null ? null :
                removeImageIds.stream().map(Long::valueOf).toList();
        return communityService.updatePost(
                id,
                new PostRequest(category, title, content, removeIds),
                images,
                Long.valueOf(jwt.getSubject())
        );
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        communityService.deletePost(id, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/{id}/likes")
    public LikeResponse like(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return communityService.toggleLike(id, true, Long.valueOf(jwt.getSubject()));
    }

    @DeleteMapping("/{id}/likes")
    public LikeResponse unlike(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return communityService.toggleLike(id, false, Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/{id}/comments")
    public PageResult<CommentResponse> comments(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return communityService.getComments(id, page, pageSize, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(
            @PathVariable Long id,
            @RequestBody CommentRequest req,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return communityService.addComment(id, req, Long.valueOf(jwt.getSubject()));
    }

    @DeleteMapping("/{id}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
            @PathVariable Long id,
            @PathVariable Long commentId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        communityService.deleteComment(id, commentId, Long.valueOf(jwt.getSubject()));
    }
}
