package dev.relayops.job;

public enum TaskType {
    EXTRACT("document.extract.result.v1"),
    REQUIREMENTS_REVIEW("requirements.review.result.v1");

    public final String schemaVersion;
    TaskType(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public static TaskType parse(String value) {
        if (value == null || value.equals("EXTRACT")) return EXTRACT;
        if (value.equals("REQUIREMENTS_REVIEW")) return REQUIREMENTS_REVIEW;
        throw DocumentInput.invalid("任务用途不支持");
    }
    public static TaskType fromSchema(String schema) {
        for (TaskType type : values()) if (type.schemaVersion.equals(schema)) return type;
        throw new IllegalStateException("UNSUPPORTED_RESULT_SCHEMA");
    }
    public static String nameIfKnown(String schema) {
        for (TaskType type : values()) if (type.schemaVersion.equals(schema)) return type.name();
        return null;
    }
}
