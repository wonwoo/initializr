/*
 * Copyright 2012-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.spring.initializr.web;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spring.initializr.generator.spring.test.ProjectAssert;
import io.spring.initializr.web.AbstractInitializrIntegrationTests.Config;
import io.spring.initializr.web.mapper.InitializrMetadataVersion;
import io.spring.initializr.web.support.InitializrMetadataUpdateStrategy;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.ExecTask;
import org.apache.tools.ant.taskdefs.Expand;
import org.apache.tools.ant.taskdefs.Untar;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Nicoll
 */
@SpringBootTest(classes = Config.class)
public abstract class AbstractInitializrIntegrationTests {

	protected static final MediaType CURRENT_METADATA_MEDIA_TYPE = InitializrMetadataVersion.V2_1.getMediaType();

	private static final ObjectMapper objectMapper = new ObjectMapper();

	public Path folder;

	@Autowired
	private RestTemplateBuilder restTemplateBuilder;

	private RestTemplate restTemplate;

	@BeforeEach
	public void before(@TempDir Path folder) {
		this.restTemplate = this.restTemplateBuilder.build();
		this.folder = folder;
	}

	protected abstract String createUrl(String context);

	/**
	 * Validate the "Content-Type" header of the specified response.
	 * @param response the response
	 * @param expected the expected result
	 */
	protected void validateContentType(ResponseEntity<String> response, MediaType expected) {
		MediaType actual = response.getHeaders().getContentType();
		assertThat(actual).isNotNull();
		assertThat(actual.isCompatibleWith(expected))
				.as("Non compatible media-type, expected " + expected + ", got " + actual).isTrue();
	}

	protected JsonNode parseJson(String text) {
		try {
			return objectMapper.readTree(text);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Invalid json", ex);
		}
	}

	protected void validateMetadata(ResponseEntity<String> response, MediaType mediaType, String version,
			JSONCompareMode compareMode) {
		try {
			validateContentType(response, mediaType);
			JSONObject json = new JSONObject(response.getBody());
			JSONObject expected = readMetadataJson(version);
			JSONAssert.assertEquals(expected, json, compareMode);
		}
		catch (JSONException ex) {
			throw new IllegalArgumentException("Invalid json", ex);
		}
	}

	protected void validateCurrentMetadata(ResponseEntity<String> response) {
		validateContentType(response, CURRENT_METADATA_MEDIA_TYPE);
		validateCurrentMetadata(response.getBody());
	}

	protected void validateCurrentMetadata(String json) {
		try {
			JSONObject expected = readMetadataJson("2.1.0");
			JSONAssert.assertEquals(expected, new JSONObject(json), JSONCompareMode.STRICT);
		}
		catch (JSONException ex) {
			throw new IllegalArgumentException("Invalid json", ex);
		}
	}

	private JSONObject readMetadataJson(String version) {
		return readJsonFrom("metadata/test-default-" + version + ".json");
	}

	/**
	 * Return a {@link ProjectAssert} for the following archive content.
	 * @param content the source content
	 * @return a project assert
	 */
	protected ProjectAssert zipProjectAssert(byte[] content) {
		return projectAssert(content, ArchiveType.ZIP);
	}

	/**
	 * Return a {@link ProjectAssert} for the following TGZ archive.
	 * @param content the source content
	 * @return a project assert
	 */
	protected ProjectAssert tgzProjectAssert(byte[] content) {
		return projectAssert(content, ArchiveType.TGZ);
	}

	protected ProjectAssert downloadZip(String context) {
		byte[] body = downloadArchive(context).getBody();
		return zipProjectAssert(body);
	}

	protected ProjectAssert downloadTgz(String context) {
		byte[] body = downloadArchive(context).getBody();
		return tgzProjectAssert(body);
	}

	protected ResponseEntity<byte[]> downloadArchive(String context) {
		return this.restTemplate.getForEntity(createUrl(context), byte[].class);
	}

	protected ResponseEntity<String> invokeHome(String userAgentHeader, String... acceptHeaders) {
		return execute("/", String.class, userAgentHeader, acceptHeaders);
	}

	protected <T> ResponseEntity<T> execute(String contextPath, Class<T> responseType, String userAgentHeader,
			String... acceptHeaders) {
		HttpHeaders headers = new HttpHeaders();
		if (userAgentHeader != null) {
			headers.set("User-Agent", userAgentHeader);
		}
		if (acceptHeaders != null) {
			List<MediaType> mediaTypes = new ArrayList<>();
			for (String acceptHeader : acceptHeaders) {
				mediaTypes.add(MediaType.parseMediaType(acceptHeader));
			}
			headers.setAccept(mediaTypes);
		}
		else {
			headers.setAccept(Collections.emptyList());
		}
		return this.restTemplate.exchange(createUrl(contextPath), HttpMethod.GET, new HttpEntity<Void>(headers),
				responseType);
	}

	protected ProjectAssert projectAssert(byte[] content, ArchiveType archiveType) {
		try {
			Path archiveFile = writeArchive(content);
			Path project = this.folder.resolve("project");
			switch (archiveType) {
			case ZIP:
				unzip(archiveFile, project);
				break;
			case TGZ:
				untar(archiveFile, project);
				break;
			}
			return new ProjectAssert(project);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot unpack archive", ex);
		}
	}

	private void untar(Path archiveFile, Path project) {
		if (!runningOnWindows()) {
			createProjectDir(project);
			ExecTask execTask = new ExecTask();
			execTask.setProject(new Project());
			execTask.setExecutable("tar");
			execTask.createArg().setValue("-C");
			execTask.createArg().setValue(project.toFile().getAbsolutePath());
			execTask.createArg().setValue("-xf");
			execTask.createArg().setValue(archiveFile.toFile().getAbsolutePath());
			execTask.execute();
		}
		else {
			Untar expand = new Untar();
			expand.setProject(new Project());
			expand.setDest(project.toFile());
			expand.setSrc(archiveFile.toFile());
			Untar.UntarCompressionMethod method = new Untar.UntarCompressionMethod();
			method.setValue("gzip");
			expand.setCompression(method);
			expand.execute();
		}
	}

	private void createProjectDir(Path project) {
		ExecTask execTask = new ExecTask();
		execTask.setProject(new Project());
		execTask.setExecutable("mkdir");
		execTask.createArg().setValue(project.toFile().getAbsolutePath());
		execTask.execute();
	}

	private void unzip(Path archiveFile, Path project) {
		if (!runningOnWindows()) {
			ExecTask execTask = new ExecTask();
			execTask.setProject(new Project());
			execTask.setExecutable("unzip");
			execTask.createArg().setValue(archiveFile.toFile().getAbsolutePath());
			execTask.createArg().setValue("-d");
			execTask.createArg().setValue(project.toFile().getAbsolutePath());
			execTask.execute();
		}
		else {
			Expand expand = new Expand();
			expand.setProject(new Project());
			expand.setDest(project.toFile());
			expand.setSrc(archiveFile.toFile());
			expand.execute();
		}
	}

	private boolean runningOnWindows() {
		return File.separatorChar == '\\';
	}

	protected Path writeArchive(byte[] body) throws IOException {
		Path archiveFile = this.folder.resolve("archive");
		Files.write(archiveFile, body);
		return archiveFile;
	}

	protected JSONObject readJsonFrom(String path) {
		try {
			ClassPathResource resource = new ClassPathResource(path);
			try (InputStream in = resource.getInputStream()) {
				String json = StreamUtils.copyToString(in, Charset.forName("UTF-8"));
				String placeholder = "";
				if (this instanceof AbstractInitializrControllerIntegrationTests) {
					placeholder = ((AbstractInitializrControllerIntegrationTests) this).host;
				}
				if (this instanceof AbstractFullStackInitializrIntegrationTests) {
					AbstractFullStackInitializrIntegrationTests test = (AbstractFullStackInitializrIntegrationTests) this;
					placeholder = test.host + ":" + test.port;
				}
				// Let's parse the port as it is random
				// TODO: put the port back somehow so it appears in stubs
				String content = json.replaceAll("@host@", placeholder);
				return new JSONObject(content);
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("Cannot read JSON from path=" + path);
		}
	}

	public RestTemplate getRestTemplate() {
		return this.restTemplate;
	}

	private enum ArchiveType {

		ZIP,

		TGZ

	}

	@EnableAutoConfiguration
	public static class Config {

		// Disable metadata fetching from spring.io
		@Bean
		public InitializrMetadataUpdateStrategy initializrMetadataUpdateStrategy() {
			return (metadata) -> metadata;
		}

	}

}
