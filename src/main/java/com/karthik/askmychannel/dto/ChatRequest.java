package com.karthik.askmychannel.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String question) {
}
