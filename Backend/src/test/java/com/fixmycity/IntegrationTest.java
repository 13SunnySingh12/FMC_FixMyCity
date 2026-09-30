package com.fixmycity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.fixmycity.ai.AiClient;
import com.fixmycity.storage.StorageService;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Full application against PostgreSQL + pgvector; one shared context keeps the suite fast.
 * MOCK/TEST INTEGRATION: Backblaze B2 and the FastAPI AI service are Mockito mocks here; both are verified
 * against the real services at runtime.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@MockitoBean(types = { StorageService.class, AiClient.class })
public @interface IntegrationTest {

}
