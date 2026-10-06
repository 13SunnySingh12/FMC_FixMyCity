package com.fixmycity.storage;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fixmycity.common.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Backblaze B2 private bucket through its S3-compatible API. This backend is the only component holding B2
 * credentials; users and the AI service read files through short-lived signed URLs.
 */
@Service
public class StorageService {

	private static final Logger log = LoggerFactory.getLogger(StorageService.class);

	private static final Pattern B2_HOST = Pattern.compile("^s3\\.([a-z0-9-]+)\\.backblazeb2\\.com$");

	private final S3Client s3;

	private final S3Presigner presigner;

	private final String bucket;

	private final Duration signedUrlTtl;

	StorageService(@Value("${fmc.storage.endpoint}") String endpoint, @Value("${fmc.storage.bucket}") String bucket,
			@Value("${fmc.storage.key-id}") String keyId, @Value("${fmc.storage.application-key}") String applicationKey,
			@Value("${fmc.storage.signed-url-ttl}") Duration signedUrlTtl) {
		if (Stream.of(endpoint, bucket, keyId, applicationKey).anyMatch(String::isBlank)) {
			throw new IllegalStateException(
					"B2 storage is not configured: set B2_ENDPOINT, B2_BUCKET, B2_KEY_ID and B2_APPLICATION_KEY");
		}
		Matcher host = B2_HOST.matcher(endpoint.strip().replaceFirst("^https?://", "").replaceAll("/+$", ""));
		if (!host.matches()) {
			throw new IllegalStateException("B2_ENDPOINT must look like s3.<region>.backblazeb2.com");
		}
		URI uri = URI.create("https://" + host.group());
		Region region = Region.of(host.group(1));
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, applicationKey));
		this.s3 = S3Client.builder()
			.endpointOverride(uri)
			.region(region)
			.credentialsProvider(credentials)
			// Only send checksums the S3 API requires, for compatibility with third-party S3 implementations.
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
			// Bound a hung B2 call so the user gets the "storage unavailable" message instead of waiting.
			.overrideConfiguration((config) -> config.apiCallAttemptTimeout(Duration.ofSeconds(30))
				.apiCallTimeout(Duration.ofSeconds(60)))
			.build();
		this.presigner = S3Presigner.builder().endpointOverride(uri).region(region).credentialsProvider(credentials).build();
		this.bucket = bucket;
		this.signedUrlTtl = signedUrlTtl;
	}

	/** Uploads as part of the current transaction: the object is deleted again if the transaction rolls back. */
	public void putInTransaction(String key, ImageFile image) {
		try {
			this.s3.putObject((request) -> request.bucket(this.bucket).key(key).contentType(image.contentType()),
					RequestBody.fromBytes(image.bytes()));
		}
		catch (SdkException ex) {
			log.warn("Upload of {} to B2 failed: {}", key, ex.getClass().getSimpleName());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"Image storage is temporarily unavailable. Please try again.");
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					deleteQuietly(key);
				}
			}

		});
	}

	public String signedUrl(String key) {
		return this.presigner
			.presignGetObject((request) -> request.signatureDuration(this.signedUrlTtl)
				.getObjectRequest((object) -> object.bucket(this.bucket).key(key)))
			.url()
			.toString();
	}

	public void deleteQuietly(String key) {
		try {
			this.s3.deleteObject((request) -> request.bucket(this.bucket).key(key));
		}
		catch (SdkException ex) {
			log.warn("Could not delete orphaned B2 object {}: {}", key, ex.getClass().getSimpleName());
		}
	}

	@PreDestroy
	void close() {
		this.s3.close();
		this.presigner.close();
	}

}
