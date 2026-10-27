package com.example.userservice.config;

import io.minio.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;

@Configuration
public class MinioConfig {
    @Bean("storageMinio")
    MinioClient storageMinio(@Value("${lab.minio.endpoint}") String endpoint,
                            @Value("${lab.minio.access-key}") String accessKey,
                            @Value("${lab.minio.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).region("us-east-1").build();
    }

    @Bean("publicMinio")
    MinioClient publicMinio(@Value("${lab.minio.public-endpoint}") String endpoint,
                           @Value("${lab.minio.access-key}") String accessKey,
                           @Value("${lab.minio.secret-key}") String secretKey) {
        // Explicit region avoids a network lookup against localhost from inside the container.
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).region("us-east-1").build();
    }

    @Bean
    @Profile("!test")
    ApplicationRunner initializeBucket(@Qualifier("storageMinio") MinioClient client,
                                       @Value("${lab.minio.bucket}") String bucket) {
        return args -> {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                try {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                } catch (io.minio.errors.ErrorResponseException error) {
                    if (!error.errorResponse().code().equals("BucketAlreadyOwnedByYou")) throw error;
                }
            }
        };
    }
}
