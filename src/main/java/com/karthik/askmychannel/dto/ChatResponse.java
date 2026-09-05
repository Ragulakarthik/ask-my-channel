package com.karthik.askmychannel.dto;

import java.util.List;

public record ChatResponse(String answer, List<Citation> citations) {
}
