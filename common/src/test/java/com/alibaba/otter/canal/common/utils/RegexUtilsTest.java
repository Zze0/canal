package com.alibaba.otter.canal.common.utils;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 正则表达式工具类 测试类
 * @author zhouzl
 * @since 2019/6/21
 */
public class RegexUtilsTest {

	/**
	 * 根据正则提取出指定字符串中所有匹配字符串
	 */
	@Test
	public void getMatchedStrs() {
		List<String> expected = Arrays.asList("AAAAA", "BBBBB\\$");
		List<String> actual = RegexUtils.getMatchedStrs("开始AAAAA结束开始BBBBB\\$结束", "(?<=开始).*?(?=结束)");
		assertEquals(expected, actual);
	}

	/**
	 * 根据正则提取出指定字符串中所有匹配字符串(可指定取值的表达式组下标)
	 */
	@Test
	public void getMatchedStrs_withGroupIndex() {
		List<String> expected = Arrays.asList("AAAAA", "BBBBB\\$");
		List<String> actual = RegexUtils.getMatchedStrs("开始1AAAAA结束1 开始2BBBBB\\$结束2", "(开始1|开始2)(.*?)(结束1|结束2)", 2);
		assertEquals(expected, actual);
	}
}