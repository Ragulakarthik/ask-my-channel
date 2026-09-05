package com.karthik.askmychannel.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ChatRequest(@NotBlank String question, List<HistoryTurn> history) {

    public ChatRequest {
        if (history == null) {
            history = List.of();
        }
    }
}
