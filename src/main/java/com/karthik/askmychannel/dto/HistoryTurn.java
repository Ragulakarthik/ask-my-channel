package com.karthik.askmychannel.dto;

/**
 * One prior question/answer pair from the same chat session, sent back by the client on each
 * request so follow-ups ("give me top 5") can be understood in context. The server itself keeps
 * no session state — the client-held history is what makes a page reload start a fresh session
 * without needing any server-side expiry logic.
 */
public record HistoryTurn(String question, String answer) {
}
