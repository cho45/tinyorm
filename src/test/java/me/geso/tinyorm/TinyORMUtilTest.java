package me.geso.tinyorm;

import static org.junit.Assert.*;

import org.junit.Test;

public class TinyORMUtilTest {

	@Test
	public void test() {
		String got = TinyORMUtils.quoteIdentifier("hogefuga\"higehige\"hagahaga", "\"");
		assertEquals("\"hogefuga\"\"higehige\"\"hagahaga\"", got);
	}

}