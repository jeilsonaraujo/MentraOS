package com.mentra.asg_client.io.file.core;

import com.mentra.asg_client.io.file.interfaces.FileOperations;
import com.mentra.asg_client.io.file.interfaces.FileMetadataOperations;
import com.mentra.asg_client.io.file.interfaces.PackageOperations;
import com.mentra.asg_client.io.file.interfaces.StorageOperations;
import com.mentra.asg_client.io.file.utils.FileOperationLogger;
import com.mentra.asg_client.io.file.managers.ThumbnailManager;
import java.io.File;

/**
 * FileManager interface following SOLID principles.
 * 
 * Single Responsibility: Manages file operations
 * Open/Closed: Extensible through implementations
 * Liskov Substitution: Implementations can be substituted
 * Interface Segregation: Composes focused interfaces
 * Dependency Inversion: Depends on abstractions
 */
public interface FileManager extends FileOperations, FileMetadataOperations, PackageOperations, StorageOperations {

    /**
     * Reserved subdirectory name inside a package directory for SDK photo captures that the
     * caller asked us NOT to keep ({@code save=false}). Files written under this directory are
     * intentionally hidden from {@link #listFiles(String)} so they cannot leak into gallery
     * counts or Wi-Fi sync responses while their upload is still in flight. The directory is
     * still walked by {@link #cleanupOldFiles(String, long)} so orphans get age-cleaned.
     */
    String SDK_PENDING_DIR_NAME = "_sdk_pending";

    /**
     * File operation result containing success status and metadata
     */
    class FileOperationResult {
        private final boolean success;
        private final String message;
        private final String filePath;
        private final long fileSize;
        private final long timestamp;
        
        public FileOperationResult(boolean success, String message, String filePath, long fileSize, long timestamp) {
            this.success = success;
            this.message = message;
            this.filePath = filePath;
            this.fileSize = fileSize;
            this.timestamp = timestamp;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getFilePath() { return filePath; }
        public long getFileSize() { return fileSize; }
        public long getTimestamp() { return timestamp; }
        
        public static FileOperationResult success(String message, String filePath) {
            return new FileOperationResult(true, message, filePath, 0, System.currentTimeMillis());
        }
        
        public static FileOperationResult success(String filePath, long fileSize) {
            return new FileOperationResult(true, "Operation completed successfully", filePath, fileSize, System.currentTimeMillis());
        }
        
        public static FileOperationResult error(String message) {
            return new FileOperationResult(false, message, null, 0, System.currentTimeMillis());
        }
    }
    
    /**
     * File metadata information
     */
    class FileMetadata {
        private final String fileName;
        private final String filePath;
        private final long fileSize;
        private final long lastModified;
        private final String mimeType;
        private final String packageName;
        
        public FileMetadata(String fileName, String filePath, long fileSize, long lastModified, String mimeType, String packageName) {
            this.fileName = fileName;
            this.filePath = filePath;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
            this.mimeType = mimeType;
            this.packageName = packageName;
        }
        
        public String getFileName() { return fileName; }
        public String getFilePath() { return filePath; }
        public long getFileSize() { return fileSize; }
        public long getLastModified() { return lastModified; }
        public String getMimeType() { return mimeType; }
        public String getPackageName() { return packageName; }
    }
    
    /**
     * Get the operation logger for performance monitoring and audit trails.
     * @return FileOperationLogger instance
     */
    FileOperationLogger getOperationLogger();
    
    /**
     * Get the default package name for file operations.
     * This is used when no specific package is provided.
     * @return Default package name
     */
    String getDefaultPackageName();
    
    /**
     * Get the default media directory for file operations.
     * @return Default media directory
     */
    File getDefaultMediaDirectory();

    /**
     * Redirect captures to a public folder, or back to the private media directory.
     *
     * <p>Passing a directory makes {@link #getDefaultMediaDirectory()} return it, so captures land
     * somewhere another app on the device can read. Passing null restores the default. The
     * directory is only honoured while it is actually writable — see the implementation, which
     * falls back rather than letting a capture fail.
     *
     * @param directory the public capture directory, or null to use the private one
     */
    void setPublicMediaDirectory(File directory);

    /**
     * The public capture directory, if one is set **and** currently usable.
     *
     * Null when the feature is off, or when the directory cannot be created or written — the caller
     * then uses the private package directory, so a missing all-files-access grant costs the
     * redirection and never the capture.
     *
     * @return the directory captures should be written to, or null for the private one
     */
    File getPublicMediaDirectory();
    
    /**
     * Get the thumbnail manager for video thumbnail operations.
     * @return ThumbnailManager instance
     */
    ThumbnailManager getThumbnailManager();
} 