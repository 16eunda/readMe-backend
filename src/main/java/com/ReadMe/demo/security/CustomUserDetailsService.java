package com.ReadMe.demo.security;

import com.ReadMe.demo.domain.UserEntity;
import com.ReadMe.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    // JWT의 subject는 userId(Long)를 String으로 저장함
    @Override
    public CustomUserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        Long id = Long.parseLong(userId);
        UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));
        return new CustomUserDetails(user);
    }
}
