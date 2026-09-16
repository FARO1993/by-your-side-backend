package com.byyourside.backend.follow;

import com.byyourside.backend.follow.dto.FollowResponse;
import com.byyourside.backend.security.UserPrincipal;
import com.byyourside.backend.user.User;
import com.byyourside.backend.user.UserRepository;
import com.byyourside.backend.user.dto.UserSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private final FollowRepository followRepository;
    private final UserRepository userRepository;

    @Transactional
    public FollowResponse follow(UserPrincipal principal, UUID targetUserId) {
        if (principal.getId().equals(targetUserId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot follow yourself");
        }

        User follower = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        User following = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Target user not found"));

        if (followRepository.existsByFollowerIdAndFollowingId(follower.getId(), following.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already following this user");
        }

        Follow follow = followRepository.save(Follow.builder()
                .follower(follower)
                .following(following)
                .build());

        return new FollowResponse(follower.getId(), following.getId(), follow.getCreatedAt());
    }

    @Transactional
    public void unfollow(UserPrincipal principal, UUID targetUserId) {
        Follow follow = followRepository.findByFollowerIdAndFollowingId(principal.getId(), targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "You are not following this user"));

        followRepository.delete(follow);
    }

    public List<UserSummary> getFollowers(UUID userId) {
        ensureUserExists(userId);

        return followRepository.findByFollowingId(userId).stream()
                .map(f -> toSummary(f.getFollower()))
                .toList();
    }

    public List<UserSummary> getFollowing(UUID userId) {
        ensureUserExists(userId);

        return followRepository.findByFollowerId(userId).stream()
                .map(f -> toSummary(f.getFollowing()))
                .toList();
    }

    private void ensureUserExists(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
    }

    private UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl());
    }
}