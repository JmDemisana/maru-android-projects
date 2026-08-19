package io.maru.helper;

class NamiMessage {
    final long id;
    final String role;
    final String content;
    final long timestamp;

    NamiMessage(long id, String role, String content, long timestamp) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }
}
