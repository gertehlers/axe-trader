package io.g3tech.axetrader;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"axe-trader.mode=backtest",
		"axe-trader.backtest.enabled=false"
})
class AxeTraderApplicationTests {

	@Test
	void contextLoads() {
	}

}
