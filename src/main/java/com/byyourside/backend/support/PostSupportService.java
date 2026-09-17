package com.byyourside.backend.support;

import com.byyourside.backend.post.Post;
import com.byyourside.backend.post.PostRepository;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.support.dto.SupportSummaryResponse;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostSupportService {

    private final PostSupportRepository postSupportRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    @Transactional
    public SupportSummaryResponse addSupport(UserPrincipal principal, UUID postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found"));

        if (postSupportRepository.existsByPostIdAndUserId(postId, principal.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already sent support to this post");
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        postSupportRepository.save(PostSupport.builder().post(post).user(user).build());

        long count = postSupportRepository.countByPostId(postId);
        return new SupportSummaryResponse(postId, count, true);
    }

    @Transactional
    public SupportSummaryResponse removeSupport(UserPrincipal principal, UUID postId) {
        if (!postRepository.existsById(postId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post not found");
        }

        PostSupport support = postSupportRepository.findByPostIdAndUserId(postId, principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You hadn't sent support to this post"));

        postSupportRepository.delete(support);

        long count = postSupportRepository.countByPostId(postId);
        return new SupportSummaryResponse(postId, count, false);
    }
}