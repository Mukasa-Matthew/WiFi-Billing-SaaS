package com.wifibilling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApiApplicationTests {

	static {
		ApiApplication.loadDotenv();
	}

	@Test
	void contextLoads() {
	}

}
