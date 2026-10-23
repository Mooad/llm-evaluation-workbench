package com.aimastery.gateway.api.dto;

import com.aimastery.gateway.core.Message;
import com.aimastery.gateway.core.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record MessageDto(
        @NotNull
        @Pattern(regexp = "system|user|assistant", message = "role doit valoir system, user ou assistant")
        String role,

        @NotBlank(message = "content ne peut pas etre vide")
        String content) {

    public Message toDomain() {
        return new Message(Role.valueOf(role.toUpperCase()), content);
    }
}
