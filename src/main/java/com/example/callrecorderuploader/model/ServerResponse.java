package com.example.callrecorderuploader.model;

import com.google.gson.annotations.SerializedName;

public class ServerResponse {

    @SerializedName("code")
    private int code;

    @SerializedName("message")
    private String message;

    @SerializedName("data")
    private DataDetails data;

    @SerializedName("timestamp")
    private long timestamp;

    @SerializedName("executeTime")
    private int executeTime;

    // Getters (and setters if needed)
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public DataDetails getData() { return data; }
    public long getTimestamp() { return timestamp; }
    public int getExecuteTime() { return executeTime; }


    public static class DataDetails {
        @SerializedName("code")
        private int dataCode; // Renamed to avoid conflict if used as inner class directly

        @SerializedName("message")
        private String dataMessage; // Renamed

        // Add any other fields that might be inside the 'data' object
        // For example, if 'data' contains call record server ID:
        // @SerializedName("callRecordServerId")
        // private String callRecordServerId;

        public int getCode() { return dataCode; }
        public String getMessage() { return dataMessage; }
        // public String getCallRecordServerId() { return callRecordServerId; }
    }

    // Convenience methods to directly access nested data if desired
    public int getDataCode() {
        return (data != null) ? data.getCode() : -1; // Return a default if data is null
    }

    public String getDataMessage() {
        return (data != null) ? data.getMessage() : null;
    }
}