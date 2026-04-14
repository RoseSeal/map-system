package com.whut.map.map_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.whut.map.map_service.chart.repository.S57TileRepository;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"llm.provider=gemini",
				"llm.gemini.api-key=test-key",
				"spring.autoconfigure.exclude="
						+ "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
						+ "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
		}
)
class MapServiceApplicationTests {

	@MockBean
	private S57TileRepository s57TileRepository;

	@Test
	void contextLoads() {
	}

}
