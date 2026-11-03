package com.asdf.board.be.community.service;

import com.asdf.board.be.community.dto.*;
import com.asdf.board.be.community.entity.*;
import com.asdf.board.be.community.repository.*;
import com.asdf.board.be.global.exception.ApiException;
import com.asdf.board.be.global.exception.ErrorCode;
import com.asdf.board.be.user.entity.User;
import com.asdf.board.be.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CommunityService {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final PostImageRepository postImageRepository;
    private final PostLikeRepository postLikeRepository;
    private final BlockRepository blockRepository;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final PostImageStorage imageStorage;

    @Value("${vitality.base-url:http://localhost:8080}")
    private String baseUrl;

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @Transactional(readOnly = true)
    public PageResult<PostResponse> getPosts(String category, int page, int pageSize) {
        Post.Category cat = category != null && !category.isBlank()
                ? Post.Category.valueOf(category) : null;
        var pageable = PageRequest.of(page - 1, pageSize);
        var result = postRepository.findVisible(cat, pageable);
        return PageResult.from(result, p -> PostResponse.ofList(p, null, baseUrl));
    }

    @Transactional(readOnly = true)
    public PageResult<PostResponse> getPosts(String category, int page, int pageSize, Long currentUserId) {
        Post.Category cat = category != null && !category.isBlank()
                ? Post.Category.valueOf(category) : null;
        var pageable = PageRequest.of(page - 1, pageSize);
        var result = postRepository.findVisible(cat, pageable);
        return PageResult.from(result, p -> PostResponse.ofList(p, currentUserId, baseUrl));
    }

    @Transactional
    public PostResponse getPost(Long postId, Long currentUserId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
        post.setViewCount(post.getViewCount() + 1);
        boolean liked = postLikeRepository.existsByPostIdAndUserId(postId, currentUserId);
        List<PostImage> images = postImageRepository.findByPostIdOrderByOrderNoAsc(postId);
        return PostResponse.ofDetail(post, currentUserId, liked, images, baseUrl);
    }

    @Transactional
    public PostResponse createPost(PostRequest req, List<MultipartFile> files, Long userId) {
        User user = getUser(userId);
        Post post = Post.builder()
                .user(user)
                .category(Post.Category.valueOf(req.category()))
                .title(req.title())
                .content(req.content())
                .build();
        post = postRepository.save(post);

        List<PostImage> images = saveImages(post, files);
        return PostResponse.ofDetail(post, userId, false, images, baseUrl);
    }

    @Transactional
    public PostResponse updatePost(Long postId, PostRequest req, List<MultipartFile> files, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
        if (!post.getUser().getId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN);

        if (req.removeImageIds() != null && !req.removeImageIds().isEmpty()) {
            List<PostImage> toDelete = postImageRepository.findByPostIdOrderByOrderNoAsc(postId)
                    .stream().filter(img -> req.removeImageIds().contains(img.getId())).toList();
            toDelete.forEach(img -> imageStorage.delete(img.getStorageKey()));
            postImageRepository.deleteByPostIdAndIdIn(postId, req.removeImageIds());
        }

        post.setCategory(Post.Category.valueOf(req.category()));
        post.setTitle(req.title());
        post.setContent(req.content());

        List<PostImage> existing = postImageRepository.findByPostIdOrderByOrderNoAsc(postId);
        List<PostImage> newImages = saveImages(post, files, existing.size() + 1);
        List<PostImage> all = new ArrayList<>(existing);
        all.addAll(newImages);

        boolean liked = postLikeRepository.existsByPostIdAndUserId(postId, userId);
        return PostResponse.ofDetail(post, userId, liked, all, baseUrl);
    }

    @Transactional
    public void deletePost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
        if (!post.getUser().getId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN);
        List<PostImage> images = postImageRepository.findByPostIdOrderByOrderNoAsc(postId);
        images.forEach(img -> imageStorage.delete(img.getStorageKey()));
        postRepository.delete(post);
    }

    @Transactional
    public LikeResponse toggleLike(Long postId, boolean like, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
        User user = getUser(userId);
        boolean already = postLikeRepository.existsByPostIdAndUserId(postId, userId);
        if (like && !already) {
            postLikeRepository.save(PostLike.builder().post(post).user(user).build());
            post.setLikeCount(post.getLikeCount() + 1);
        } else if (!like && already) {
            postLikeRepository.deleteByPostIdAndUserId(postId, userId);
            post.setLikeCount(Math.max(0, post.getLikeCount() - 1));
        }
        return new LikeResponse(post.getLikeCount(), like);
    }

    @Transactional(readOnly = true)
    public PageResult<CommentResponse> getComments(Long postId, int page, int pageSize, Long currentUserId) {
        var pageable = PageRequest.of(page - 1, pageSize);
        var result = commentRepository.findByPostIdAndHiddenAtIsNullOrderByCreatedAtAsc(postId, pageable);
        return PageResult.from(result, c -> CommentResponse.of(c, currentUserId));
    }

    @Transactional
    public CommentResponse addComment(Long postId, CommentRequest req, Long userId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND));
        User user = getUser(userId);
        Comment parent = null;
        if (req.parentId() != null) {
            parent = commentRepository.findById(req.parentId())
                    .orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
        }
        Comment saved = commentRepository.save(Comment.builder().post(post).user(user).parent(parent).content(req.content()).build());
        post.setCommentCount(post.getCommentCount() + 1);
        return CommentResponse.of(saved, userId);
    }

    @Transactional
    public void deleteComment(Long postId, Long commentId, Long userId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND));
        if (!comment.getPost().getId().equals(postId)) throw new ApiException(ErrorCode.COMMENT_NOT_FOUND);
        if (!comment.getUser().getId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN);
        comment.setIsDeleted(true);
        Post post = comment.getPost();
        post.setCommentCount(Math.max(0, post.getCommentCount() - 1));
    }

    @Transactional
    public void report(ReportRequest req, Long userId) {
        User reporter = getUser(userId);
        Report.TargetType type;
        Report.Reason reason;
        try {
            type = Report.TargetType.valueOf(req.targetType());
            reason = Report.Reason.valueOf(req.reason());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED);
        }
        if (req.targetId() == null) throw new ApiException(ErrorCode.VALIDATION_FAILED);
        Long ownerId = switch (type) {
            case post -> postRepository.findById(req.targetId()).orElseThrow(() -> new ApiException(ErrorCode.POST_NOT_FOUND)).getUser().getId();
            case comment -> commentRepository.findById(req.targetId()).orElseThrow(() -> new ApiException(ErrorCode.COMMENT_NOT_FOUND)).getUser().getId();
        };
        if (ownerId.equals(userId)) throw new ApiException(ErrorCode.CANNOT_REPORT_SELF);
        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetId(userId, type, req.targetId())) {
            throw new ApiException(ErrorCode.ALREADY_REPORTED);
        }
        reportRepository.save(Report.builder()
                .reporter(reporter)
                .targetType(type)
                .targetId(req.targetId())
                .reason(reason)
                .detail(req.detail())
                .build());
    }

    @Transactional(readOnly = true)
    public PageResult<BlockedMemberResponse> getBlocks(int page, int pageSize, Long userId) {
        var pageable = PageRequest.of(page - 1, pageSize);
        var result = blockRepository.findByBlockerId(userId, pageable);
        return PageResult.from(result, BlockedMemberResponse::from);
    }

    @Transactional
    public void block(Long targetUserId, Long userId) {
        if (blockRepository.existsByBlockerIdAndBlockedId(userId, targetUserId)) {
            throw new ApiException(ErrorCode.ALREADY_BLOCKED);
        }
        User blocker = getUser(userId);
        User blocked = getUser(targetUserId);
        blockRepository.save(Block.builder().blocker(blocker).blocked(blocked).build());
    }

    @Transactional
    public void unblock(Long targetUserId, Long userId) {
        blockRepository.deleteByBlockerIdAndBlockedId(userId, targetUserId);
    }

    private List<PostImage> saveImages(Post post, List<MultipartFile> files) {
        return saveImages(post, files, 1);
    }

    private List<PostImage> saveImages(Post post, List<MultipartFile> files, int startOrder) {
        List<PostImage> result = new ArrayList<>();
        if (files == null) return result;
        int order = startOrder == 0 ? 1 : startOrder;
        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            try {
                String key = imageStorage.store(file);
                PostImage img = PostImage.builder().post(post).storageKey(key).orderNo(order++).build();
                result.add(postImageRepository.save(img));
            } catch (IOException e) {
                throw new ApiException(ErrorCode.INTERNAL_ERROR);
            }
        }
        return result;
    }
}
