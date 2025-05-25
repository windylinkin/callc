package com.example.callrecorderuploader.model; // Changed package

import java.util.Objects;

public class RecordingEntry {
    private String filePath; // Can be actual path or URI string for temp files
    private String fileName;
    private long creationTimestamp;
    private String uploadStatus; // e.g., "排队中", "上传中", "上传成功: [serverMsg]", "上传失败: [errorMsg]"
    private String workRequestId; // To link with WorkManager's WorkInfo
    private long uploadSuccessTime; // Timestamp of successful upload
    private String serverResponseMessage; // Detailed message from server data field

    public RecordingEntry(String filePath, String fileName, long creationTimestamp, String uploadStatus, String workRequestId, long uploadSuccessTime) {
        this.filePath = filePath;
        this.fileName = fileName;
        this.creationTimestamp = creationTimestamp;
        this.uploadStatus = uploadStatus;
        this.workRequestId = workRequestId;
        this.uploadSuccessTime = uploadSuccessTime;
    }

    // Getters
    public String getFilePath() { return filePath; }
    public String getFileName() { return fileName; }
    public long getCreationTimestamp() { return creationTimestamp; }
    public String getUploadStatus() { return uploadStatus; }
    public String getWorkRequestId() { return workRequestId; }
    public long getUploadSuccessTime() { return uploadSuccessTime; }
    public String getServerResponseMessage() { return serverResponseMessage; }

    // Setters
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setCreationTimestamp(long creationTimestamp) { this.creationTimestamp = creationTimestamp; }
    public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }
    public void setWorkRequestId(String workRequestId) { this.workRequestId = workRequestId; }
    public void setUploadSuccessTime(long uploadSuccessTime) { this.uploadSuccessTime = uploadSuccessTime; }
    public void setServerResponseMessage(String serverResponseMessage) { this.serverResponseMessage = serverResponseMessage; }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RecordingEntry that = (RecordingEntry) o;
        return Objects.equals(filePath, that.filePath); // Primarily identify by path
    }

    @Override
    public int hashCode() {
        return Objects.hash(filePath);
    }

    @Override
    public String toString() {
        return "RecordingEntry{" +
                "fileName='" + fileName + '\'' +
                ", status='" + uploadStatus + '\'' +
                ", workId='" + workRequestId + '\'' +
                '}';
    }
}