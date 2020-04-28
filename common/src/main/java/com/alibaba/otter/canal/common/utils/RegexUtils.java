package com.alibaba.otter.canal.common.utils;

import org.apache.commons.lang.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式工具类
 *
 * @author zhouzl
 * @since 2018/10/8
 */
public class RegexUtils {

	private RegexUtils(){}

	/**
	 * 根据正则提取出指定字符串中所有匹配字符串
	 *
	 * @author zhouzl
	 * @since 2018/10/8

	 * @param str 字符串
	 * @param regex 正则表达式
	 * @return
	 */
	public static List<String> getMatchedStrs(String str, String regex) {
		return getMatchedStrs(str, regex, null);
	}

	/**
	 * 根据正则提取出指定字符串中所有匹配字符串
	 *
	 * @author zhouzl
	 * @since 2018/10/8

	 * @param str 字符串
	 * @param regex 正则表达式
	 * @param groupIndex 要取值的表达式组下标(为null就取整段表达式匹配结果)
	 * @return
	 */
	public static List<String> getMatchedStrs(String str, String regex, Integer groupIndex) {
		if (StringUtils.isBlank(str) || StringUtils.isBlank(regex)){
			return Collections.emptyList();
		}
		//正则匹配到的字符串集合
		List<String> matchedStrs = new ArrayList<>();

		Matcher matcher = Pattern.compile(regex).matcher(str);
		while (matcher.find()){
			//获取匹配到的当前组的字符串
			String group = null==groupIndex ? matcher.group() : matcher.group(groupIndex);

			if (StringUtils.isNotBlank(group)){
				matchedStrs.add(group);
			}
		}

		return CollectionUtils.distinct(matchedStrs);
	}

	/**
	 * 根据正则提取出指定字符串中首个匹配字符串
	 *
	 * @author zhouzl
	 * @since 2018/10/8

	 * @param str 字符串
	 * @param regex 正则表达式
	 * @param groupIndex 要取值的表达式组下标(为null就取整段表达式匹配结果)
	 * @return
	 */
	public static String getFirstMatchedStr(String str, String regex, Integer groupIndex) {
		List<String> matchedStrs = getMatchedStrs(str, regex, groupIndex);
		return CollectionUtils.isNotEmpty(matchedStrs) ? matchedStrs.get(0) : "";
	}
}
