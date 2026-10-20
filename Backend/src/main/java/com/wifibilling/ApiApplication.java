package com.wifibilling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

@SpringBootApplication
public class ApiApplication {

	static {
		loadDotenv();
	}

	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}

	/**
	 * Automatically loads environment variables from a local .env file
	 * into System properties so Spring Boot resolves them seamlessly
	 * both during tests and runtime.
	 */
	public static void loadDotenv() {
		String[] candidatePaths = {
			".env",
			"Backend/.env",
			"../Backend/.env",
			"../.env"
		};

		for (String path : candidatePaths) {
			File envFile = new File(path);
			if (envFile.exists() && envFile.isFile()) {
				try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
					String line;
					int count = 0;
					while ((line = reader.readLine()) != null) {
						line = line.trim();
						if (line.isEmpty() || line.startsWith("#")) {
							continue;
						}
						int equalIndex = line.indexOf('=');
						if (equalIndex > 0) {
							String key = line.substring(0, equalIndex).trim();
							String value = line.substring(equalIndex + 1).trim();
							// Strip surrounding quotes if present
							if ((value.startsWith("\"") && value.endsWith("\"")) ||
							    (value.startsWith("'") && value.endsWith("'"))) {
								value = value.substring(1, value.length() - 1);
							}
							if (System.getenv(key) == null && System.getProperty(key) == null) {
								System.setProperty(key, value);
								// Also set lowercase dot-separated key for exact Spring PropertySource matches
								String dotKey = key.toLowerCase().replace('_', '.');
								if (!dotKey.equals(key) && System.getProperty(dotKey) == null) {
									System.setProperty(dotKey, value);
								}
								count++;
							}
						}
					}
					System.out.println("[Environment] Successfully loaded " + count + " properties from: " + envFile.getAbsolutePath());
					break;
				} catch (IOException e) {
					System.err.println("[Environment] Failed reading .env file: " + e.getMessage());
				}
			}
		}
	}
}
