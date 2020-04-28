package com.alibaba.otter.canal.common.utils;

import org.hamcrest.collection.IsCollectionWithSize;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsCollectionContaining;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * CollectionUtils单元测试
 * @author zhouzl
 * @since 2019/4/19
 */
public class CollectionUtilsTest {

	/**
	 * 获取分页最大页数
	 */
	@Test
	public void getMaxPageIndex() {
		List<Integer> list = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element++);
		}
		//最大页数
		int maxPageIndex = CollectionUtils.getMaxPageIndex(list, 10);

		Assert.assertEquals(3, maxPageIndex);
	}

	/**
	 * 获取指定页的数据列表
	 */
	@Test
	public void getPage() {
		List<Integer> list = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element++);
		}

		//页码
		int pageIndex = 2;
		//每页大小
		int pageSize = 10;

		List<Integer> page = CollectionUtils.getPage(list, pageSize, pageIndex, null);
		Assert.assertNotNull(page);
		//集合元素数断言为10
		Assert.assertThat(page, IsCollectionWithSize.hasSize(pageSize));

		//期望值
		int expected = pageSize*(pageIndex-1)+1;

		for (Integer actual : page) {
			Assert.assertEquals((Integer)expected++, actual);
		}
	}

	/**
	 * 分页式遍历list
	 */
	@Test
	public void forEachPage() {
		List<Integer> list = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element++);
		}

		//每页大小
		int pageSize = 10;

		//最大页数
		int maxPageIndex = CollectionUtils.getMaxPageIndex(list, pageSize);

		//已遍历页数计数器
		CountDownLatch countDown4Page = new CountDownLatch(maxPageIndex);
		//已遍历元素计数器
		CountDownLatch countDown4Elements = new CountDownLatch(list.size());

		CollectionUtils.forEachPage(page->{
			Assert.assertNotNull(page);
			//页码
			long pageIndex = maxPageIndex-countDown4Page.getCount()+1;

			//期望值
			long expected = pageSize*(pageIndex-1)+1;

			for (Integer actual : page) {
				Assert.assertEquals(expected++, (long)actual);
				countDown4Elements.countDown();
			}
			countDown4Page.countDown();
		}, list, pageSize);

		Assert.assertEquals(0, countDown4Page.getCount());
		Assert.assertEquals(0, countDown4Elements.getCount());
	}

	/**
	 * 分页式遍历list-至少执行一次
	 */
	@Test
	public void forEachPage_atLeastOnce() {

		CountDownLatch countDownLatch = new CountDownLatch(1);

		CollectionUtils.forEachPage(page->{
			Assert.assertNull(page);
			countDownLatch.countDown();
		}, null, 10, true);

		Assert.assertEquals(0, countDownLatch.getCount());
	}

	/**
	 * 转换Map
	 */
	@Test
	public void toMap() {
		List<Integer> list = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element++);
		}

		Map<Integer, String> map = CollectionUtils.toMap(list, Function.identity(), e -> ("value-" + e));
		Assert.assertNotNull(map);
		//断言元素总数
		Assert.assertThat(map.keySet(), IsCollectionWithSize.hasSize(list.size()));

		//元素断言
		list.forEach(expected -> Assert.assertEquals(("value-" + expected), map.get(expected)));
	}

	/**
	 * 转换Map 【加过滤器】
	 */
	@Test
	public void toMap_withFilter() {
		List<Integer> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			list.add(element++);
		}

		//过滤掉1
		Predicate<Integer> filter = e -> e != 1;

		Map<Integer, String> map = CollectionUtils.toMap(list, filter, Function.identity(), e -> ("value-" + e));
		Assert.assertNotNull(map);

		//断言元素总数
		list.removeIf(filter.negate());
		Assert.assertThat(map.keySet(), IsCollectionWithSize.hasSize(list.size()));

		//元素断言
		list.forEach(expected -> Assert.assertEquals(("value-" + expected), map.get(expected)));
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的list集合）
	 */
	@Test
	public void toListMap() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//工具类转换的结果
		Map<String, List<String>> listMap4Actual = CollectionUtils.toListMap(list, map -> map.get("key"), map -> map.get("value"));
		Assert.assertNotNull(listMap4Actual);

		//单元测试手动的转换
		Map<String, List<String>> listMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {
			String key = map.get("key");
			String value = map.get("value");

			List<String> values = listMap4Expected.get(key);
			values = null==values ? new ArrayList<>() : values;
			values.add(value);

			listMap4Expected.put(key, values);
		}

		//断言元素总数
		Assert.assertThat(listMap4Actual.keySet(), IsCollectionWithSize.hasSize(listMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(listMap4Expected, listMap4Actual);
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的list集合）【加过滤器】
	 */
	@Test
	public void toListMap_withFilter() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//过滤掉key为1的
		Predicate<Map<String,String>> filter = map -> !("1".equals(map.get("key")));

		//工具类转换的结果
		Map<String, List<String>> listMap4Actual = CollectionUtils.toListMap(list, filter, map -> map.get("key"), map -> map.get("value"));
		Assert.assertNotNull(listMap4Actual);

		//单元测试手动的转换
		Map<String, List<String>> listMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {

			if (! filter.test(map)){
				//忽略要过滤的
				continue;
			}

			String key = map.get("key");
			String value = map.get("value");

			List<String> values = listMap4Expected.get(key);
			values = null==values ? new ArrayList<>() : values;
			values.add(value);

			listMap4Expected.put(key, values);
		}

		//断言元素总数
		Assert.assertThat(listMap4Actual.keySet(), IsCollectionWithSize.hasSize(listMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(listMap4Expected, listMap4Actual);
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的list集合）【加过滤器,集合去重】
	 */
	@Test
	public void toListMap_withFilter_distinctValue() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}

			//加入重复值
			Map<String,String> map = new HashMap<>();
			map.put("key", Integer.toString(element));
			map.put("value", element + "-0");
			list.add(map);

			element++;
		}

		//过滤掉key为1的
		Predicate<Map<String,String>> filter = map -> !("1".equals(map.get("key")));

		//工具类转换的结果
		Map<String, List<String>> listMap4Actual = CollectionUtils.toListMap(list, filter, true, map -> map.get("key"), map -> map.get("value"));
		Assert.assertNotNull(listMap4Actual);

		//单元测试手动的转换
		Map<String, List<String>> listMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {

			if (! filter.test(map)){
				//忽略要过滤的
				continue;
			}

			String key = map.get("key");
			String value = map.get("value");

			List<String> values = listMap4Expected.get(key);
			values = null==values ? new ArrayList<>() : values;

			if (! values.contains(value)){
				values.add(value);
			}

			listMap4Expected.put(key, values);
		}

		//断言元素总数
		Assert.assertThat(listMap4Actual.keySet(), IsCollectionWithSize.hasSize(listMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(listMap4Expected, listMap4Actual);
	}

	/**
	 * 转换有序的集合Map（key是指定属性值，value是指定属性值的list集合）
	 */
	@Test
	public void toLinkedListMap() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//工具类转换的结果
		Map<String, List<String>> linkedListMap4Actual = CollectionUtils.toLinkedListMap(list, map -> map.get("key"), map -> map.get("value"));
		Assert.assertNotNull(linkedListMap4Actual);

		//单元测试手动的转换结果的key集合
		List<String> keys4Expected = new ArrayList<>();
		//单元测试手动的转换结果
		Map<String, List<String>> linkedListMap4Expected = new LinkedHashMap<>();

		for (Map<String, String> map : list) {
			String key = map.get("key");
			String value = map.get("value");

			List<String> values = linkedListMap4Expected.get(key);
			values = null==values ? new ArrayList<>() : values;
			values.add(value);

			linkedListMap4Expected.put(key, values);
			if (! keys4Expected.contains(key)){
				keys4Expected.add(key);
			}
		}

		//工具类转换结果map的key集合
		List<String> keys4Actual = new ArrayList<>(linkedListMap4Actual.keySet());

		//断言key排序
		Assert.assertEquals(keys4Expected, keys4Actual);

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(linkedListMap4Expected, linkedListMap4Actual);
	}

	/**
	 * 转换有序的集合Map（key是指定属性值，value是指定属性值的list集合）【加过滤器】
	 */
	@Test
	public void toLinkedListMap_withFilter() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//过滤掉key为1的
		Predicate<Map<String,String>> filter = map -> !("1".equals(map.get("key")));

		//工具类转换的结果
		Map<String, List<String>> linkedListMap4Actual = CollectionUtils.toLinkedListMap(list, filter, map -> map.get("key"), map -> map.get("value"));
		Assert.assertNotNull(linkedListMap4Actual);

		//单元测试手动的转换结果的key集合
		List<String> keys4Expected = new ArrayList<>();
		//单元测试手动的转换结果
		Map<String, List<String>> linkedListMap4Expected = new LinkedHashMap<>();

		for (Map<String, String> map : list) {

			if (! filter.test(map)){
				//忽略要过滤的
				continue;
			}

			String key = map.get("key");
			String value = map.get("value");

			List<String> values = linkedListMap4Expected.get(key);
			values = null==values ? new ArrayList<>() : values;
			values.add(value);

			linkedListMap4Expected.put(key, values);
			if (! keys4Expected.contains(key)){
				keys4Expected.add(key);
			}
		}

		//工具类转换结果map的key集合
		List<String> keys4Actual = new ArrayList<>(linkedListMap4Actual.keySet());

		//断言key排序
		Assert.assertEquals(keys4Expected, keys4Actual);

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(linkedListMap4Expected, linkedListMap4Actual);
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的set集合）
	 */
	@Test
	public void toSetMap() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//工具类转换的结果
		Map<String, Set<String>> setMap4Actual = CollectionUtils.toSetMap(list, map -> map.get("key"), map -> map.get("value"));
		Assert.assertNotNull(setMap4Actual);

		//单元测试手动的转换
		Map<String, Set<String>> setMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {

			String key = map.get("key");
			String value = map.get("value");

			Set<String> values = setMap4Expected.get(key);
			values = null==values ? new HashSet<>() : values;
			values.add(value);

			setMap4Expected.put(key, values);
		}

		//断言元素总数
		Assert.assertThat(setMap4Actual.keySet(), IsCollectionWithSize.hasSize(setMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(setMap4Expected, setMap4Actual);
	}

	/**
	 * 转换Map
	 */
	@Test
	public void toNestedMap() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			Map<String,String> map = new HashMap<>();
			map.put("key", Integer.toString(element));
			map.put("value", element+"-Value");
			list.add(map);
			element++;
		}

		//获取内层map的key和value的函数声明
		Function<Map<String, String>, String> nestedKeyGetter = map -> "nested-"+map.get("key");
		Function<Map<String, String>, String> nestedValueGetter = map -> "nested-"+map.get("value");

		//工具类转换的结果
		Map<String, Map<String, String>> listMap4Actual =
				CollectionUtils.toNestedMap(
						list,
						map -> map.get("key"),
						nestedKeyGetter,
						nestedValueGetter);

		Assert.assertNotNull(listMap4Actual);

		//单元测试手动的转换
		Map<String, Map<String, String>> listMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {
			String key = map.get("key");
			String nestedKey = nestedKeyGetter.apply(map);
			String nestedValue = nestedValueGetter.apply(map);

			Map<String, String> nestedMap = listMap4Expected.get(key);
			nestedMap = null==nestedMap ? new HashMap<>() : nestedMap;

			nestedMap.put(nestedKey, nestedValue);

			listMap4Expected.put(key, nestedMap);
		}

		//断言元素总数
		Assert.assertThat(listMap4Actual.keySet(), IsCollectionWithSize.hasSize(listMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(listMap4Expected, listMap4Actual);
	}

	/**
	 * 转换嵌套的集合Map（key是指定属性值，value是一个内层Map（key是指定属性值，value是指定属性值的List集合） ）
	 * 例子如： Map< String, Map< String, List< Object > > >
	 */
	@Test
	public void toNestedListMap() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//获取内层map的key和value的函数声明
		Function<Map<String, String>, String> nestedKeyGetter = map -> "nested-"+map.get("key");
		Function<Map<String, String>, String> nestedValueGetter = map -> "nested-"+map.get("value");

		//工具类转换的结果
		Map<String, Map<String, List<String>>> listMap4Actual =
				CollectionUtils.toNestedListMap(
						list,
						map -> map.get("key"),
						nestedKeyGetter,
						nestedValueGetter);

		Assert.assertNotNull(listMap4Actual);

		//单元测试手动的转换
		Map<String, Map<String, List<String>>> listMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {
			String key = map.get("key");
			String nestedKey = nestedKeyGetter.apply(map);
			String nestedValue = nestedValueGetter.apply(map);

			Map<String, List<String>> nestedMap = listMap4Expected.get(key);
			nestedMap = null==nestedMap ? new HashMap<>() : nestedMap;

			List<String> nestedList = nestedMap.get(nestedKey);
			nestedList = null==nestedList ? new ArrayList<>() : nestedList;
			nestedList.add(nestedValue);

			nestedMap.put(nestedKey, nestedList);
			listMap4Expected.put(key, nestedMap);
		}

		//断言元素总数
		Assert.assertThat(listMap4Actual.keySet(), IsCollectionWithSize.hasSize(listMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(listMap4Expected, listMap4Actual);
	}

	/**
	 * 转换嵌套的集合Map（key是指定属性值，value是一个内层Map（key是指定属性值，value是指定属性值的Set集合） ）
	 * 例子如： Map< String, Map< String, Set< Object > > >
	 */
	@Test
	public void toNestedSetMap() {
		List<Map<String,String>> list = new ArrayList<>();
		int element=1;
		while (element<=11){
			for (int elementValue = 0; elementValue < 3; elementValue++) {
				Map<String,String> map = new HashMap<>();
				map.put("key", Integer.toString(element));
				map.put("value", element + "-" + elementValue);
				list.add(map);
			}
			element++;
		}

		//获取内层map的key和value的函数声明
		Function<Map<String, String>, String> nestedKeyGetter = map -> "nested-"+map.get("key");
		Function<Map<String, String>, String> nestedValueGetter = map -> "nested-"+map.get("value");

		//工具类转换的结果
		Map<String, Map<String, Set<String>>> setMap4Actual =
				CollectionUtils.toNestedSetMap(
						list,
						map -> map.get("key"),
						nestedKeyGetter,
						nestedValueGetter);

		Assert.assertNotNull(setMap4Actual);

		//单元测试手动的转换
		Map<String, Map<String, Set<String>>> setMap4Expected = new HashMap<>();
		for (Map<String, String> map : list) {
			String key = map.get("key");
			String nestedKey = nestedKeyGetter.apply(map);
			String nestedValue = nestedValueGetter.apply(map);

			Map<String, Set<String>> nestedMap = setMap4Expected.get(key);
			nestedMap = null==nestedMap ? new HashMap<>() : nestedMap;

			Set<String> nestedList = nestedMap.get(nestedKey);
			nestedList = null==nestedList ? new HashSet<>() : nestedList;
			nestedList.add(nestedValue);

			nestedMap.put(nestedKey, nestedList);
			setMap4Expected.put(key, nestedMap);
		}

		//断言元素总数
		Assert.assertThat(setMap4Actual.keySet(), IsCollectionWithSize.hasSize(setMap4Expected.size()));

		//直接根据Map的equals方法实现进行元素相等断言
		Assert.assertEquals(setMap4Expected, setMap4Actual);
	}

	/**
	 * 追加元素到集合
	 */
	@Test
	public void addToList() {
		List<Integer> expected = new ArrayList<>();
		expected.add(1);

		List<Integer> actual = CollectionUtils.addToList(expected, 2);
		Assert.assertSame(expected, actual);
		Assert.assertThat(actual, IsCollectionContaining.hasItem(2));

		expected.remove(0);
		actual = CollectionUtils.addToList(null, 2);
		Assert.assertEquals(expected, actual);
		Assert.assertThat(actual, IsCollectionContaining.hasItem(2));
	}

	/**
	 * 追加元素到集合
	 */
	@Test
	public void addToSet() {
		Set<Integer> expected = new HashSet<>();
		expected.add(1);

		Set<Integer> actual = CollectionUtils.addToSet(expected, 2);
		Assert.assertSame(expected, actual);
		Assert.assertThat(actual, IsCollectionContaining.hasItem(2));

		expected.remove(1);
		actual = CollectionUtils.addToSet(null, 2);
		Assert.assertEquals(expected, actual);
		Assert.assertThat(actual, IsCollectionContaining.hasItem(2));
	}

	/**
	 * 批量消费多个集合
	 */
	@Test
	public void forEachMultiple() {
		List<Integer> list = new ArrayList<>();
		List<Integer> list2 = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element);
			element++;
		}
		while (element<=42){
			list2.add(element);
			element++;
		}

		List<Integer> actualList = new ArrayList<>();
		CollectionUtils.forEachMultiple(e -> actualList.add((Integer)e), list, list2);

		list.addAll(list2);
		Assert.assertEquals(list, actualList);
	}

	/**
	 * 集合去重
	 */
	@Test
	public void distinct() {
		List<Integer> list = new ArrayList<>();
		list.add(1);
		list.add(2);
		list.add(3);
		list.add(2);

		list = CollectionUtils.distinct(list);
		long count = list.stream().filter(element -> 2 == element).count();
		Assert.assertEquals(1, count);

		//断言下标值
		Assert.assertEquals(1, list.indexOf(2));
	}

	/**
	 * 集合反向去重（保留最后1次出现的元素）
	 */
	@Test
	public void distinctReversed() {
		List<Integer> list = new ArrayList<>();
		list.add(1);
		list.add(2);
		list.add(3);
		list.add(2);

		list = CollectionUtils.distinctReversed(list);
		long count = list.stream().filter(element -> 2 == element).count();
		Assert.assertEquals(1, count);

		//断言下标值
		Assert.assertEquals(list.size()-1, list.indexOf(2));
	}

	/**
	 * 追加其他集合的元素到 指定集合
	 */
	@Test
	public void addAll() {
		List<Integer> list = new ArrayList<>();
		List<Integer> list2 = new ArrayList<>();
		List<Integer> list3 = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element);
			element++;
		}
		while (element<=42){
			list2.add(element);
			element++;
		}
		while (element<=63){
			list3.add(element);
			element++;
		}

		List<Integer> actualList = new ArrayList<>(list);
		CollectionUtils.addAll(actualList, list2, list3);

		list.addAll(list2);
		list.addAll(list3);
		Assert.assertEquals(list, actualList);
	}

	/**
	 * 追加其他集合的元素到 指定集合【加元素处理器，在追加到指定集合前可以做些特殊的处理】
	 */
	@Test
	public void addAll_withHandler() {
		List<Integer> list = new ArrayList<>();
		List<Integer> list2 = new ArrayList<>();
		List<Integer> list3 = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element);
			element++;
		}
		while (element<=42){
			list2.add(element);
			element++;
		}
		while (element<=63){
			list3.add(element);
			element++;
		}

		//元素处理器，在追加到指定集合前可以做些特殊的处理
		Function<Integer, Integer> handler = e -> e * 10;

		//实际值
		List<Integer> actualList = new ArrayList<>(list);
		CollectionUtils.addAll(actualList, handler, list2, list3);

		//期望值
		List<Integer> expectedList = new ArrayList<>(list);
		list2.forEach(e -> expectedList.add(handler.apply(e)));
		list3.forEach(e -> expectedList.add(handler.apply(e)));

		Assert.assertEquals(expectedList, actualList);
	}

	/**
	 * 追加其他集合的元素到 指定集合【加元素处理器，在追加到指定集合前可以做些特殊的处理】【加元素过滤器，过滤一下不需要处理的元素】
	 */
	@Test
	public void addAll_withHandler_withFilter() {
		List<Integer> list = new ArrayList<>();
		List<Integer> list2 = new ArrayList<>();
		List<Integer> list3 = new ArrayList<>();
		int element=1;
		while (element<=21){
			list.add(element);
			element++;
		}
		while (element<=42){
			list2.add(element);
			element++;
		}
		while (element<=63){
			list3.add(element);
			element++;
		}

		//元素处理器，在追加到指定集合前可以做些特殊的处理
		Function<Integer, Integer> handler = e -> e * 10;

		//元素过滤器，过滤一下不需要处理的元素
		Predicate<Integer> filter = e -> e>=43 && e<=63;

		//实际值
		List<Integer> actualList = new ArrayList<>(list);
		CollectionUtils.addAll(actualList, handler, filter, list2, list3);

		//期望值
		List<Integer> expectedList = new ArrayList<>(list);
		list3.forEach(e -> expectedList.add(handler.apply(e)));

		Assert.assertEquals(expectedList, actualList);
	}

	/**
	 * 从集合1中扣除集合2中相符的元素
	 */
	@Test
	public void subtract() {
		List<Integer> list = new ArrayList<>();
		list.add(1);
		list.add(2);
		list.add(3);

		List<Integer> list2 = new ArrayList<>();
		list2.add(1);

		List<Integer> actualList = CollectionUtils.subtract(list, list2);

		list.removeIf(e->e==1);
		Assert.assertEquals(list, actualList);
	}

	/**
	 * 合并两个集合
	 */
	@Test
	public void union() {
		List<Integer> list = new ArrayList<>();
		list.add(1);
		list.add(2);
		list.add(3);

		List<Integer> list2 = new ArrayList<>();
		list2.add(1);
		list2.add(4);

		List<Integer> actualList = CollectionUtils.union(list, list2);
		actualList.sort(Integer::compareTo);

		//期望值
		list.addAll(list2);
		List<Integer> expectedList = list.stream().distinct().sorted(Integer::compareTo).collect(Collectors.toList());

		Assert.assertEquals(expectedList, actualList);
	}

	/**
	 * 字符串拼接
	 */
	@Test
	public void joining() {
		List<Integer> list = new ArrayList<>();
		list.add(1);
		list.add(2);
		list.add(3);

		String actual = CollectionUtils.joining(list, ",");

		Assert.assertEquals("1,2,3", actual);
	}

	/**
	 * 字符串拼接【加对象转字符串的函数】
	 */
	@Test
	public void joining_withGetter() {
		List<Map<String, Integer>> list = new ArrayList<>();
		list.add(Collections.singletonMap("value", 1));
		list.add(Collections.singletonMap("value", 2));
		list.add(Collections.singletonMap("value", 3));

		String actual = CollectionUtils.joining(list, map -> map.get("value").toString(), ",");

		Assert.assertEquals("1,2,3", actual);
	}

	/**
	 * 字符串拼接【加 过滤 和 对象转字符串的函数】
	 */
	@Test
	public void joining_withFilter_withGetter() {
		List<Map<String, Integer>> list = new ArrayList<>();
		list.add(Collections.singletonMap("value", 1));
		list.add(Collections.singletonMap("value", 2));
		list.add(Collections.singletonMap("value", 3));

		String actual = CollectionUtils.joining(list, map -> 2!=map.get("value"), map -> map.get("value").toString(), ",");

		Assert.assertEquals("1,3", actual);
	}

	/**
	 * 分割字符串成集合
	 */
	@Test
	public void splitString() {
		String str = "1,2,3";

		List<String> actual = CollectionUtils.splitString(str, ",");

		Assert.assertEquals(Arrays.asList("1","2","3"), actual);
	}

	/**
	 * 分割字符串，然后进行循环操作
	 */
	@Test
	public void splitStringToForEach() {
		String str = "1,2,3,4";

		List<String> actual = new ArrayList<>();

		CollectionUtils.splitStringToForEach(str, ",", actual::add);

		Assert.assertEquals(Arrays.asList("1","2","3","4"), actual);
	}

	/**
	 * 消费集合
	 */
	@Test
	public void forEach() {
		List<Integer> expected = new ArrayList<>();
		expected.add(1);
		expected.add(2);
		expected.add(3);

		List<Integer> actual = new ArrayList<>();

		CollectionUtils.forEach(actual::add, expected);

		Assert.assertEquals(expected, actual);
	}

	/**
	 * 消费集合 [还有下标值]
	 */
	@Test
	public void forEachWithIndex() {
		List<Integer> expected = new ArrayList<>();
		expected.add(1);
		expected.add(2);
		expected.add(3);

		List<Integer> actual = new ArrayList<>();
		List<Integer> actualIndexs = new ArrayList<>();

		CollectionUtils.forEachWithIndex((i, index) -> {
			actual.add(i);
			actualIndexs.add(index);
		}, expected);

		Assert.assertEquals(expected, actual);
		Assert.assertEquals(Arrays.asList(0,1,2), actualIndexs);
	}

	/**
	 * 获取对象长度（如Map、Collection、Iterable、数组、Iterator、Enumeration等等）
	 */
	@Test
	public void size() {
		Assert.assertEquals(0, CollectionUtils.size(null));
		Assert.assertEquals(3, CollectionUtils.size(Arrays.asList(1,2,3)));
		Assert.assertEquals(1, CollectionUtils.size(Collections.singletonMap("key","value")));
	}

	/**
	 * 集合流化
	 */
	@Test
	public void stream() {
		Assert.assertNotNull(CollectionUtils.stream(null));
		Assert.assertEquals(0, CollectionUtils.stream(null).count());

		Assert.assertNotNull(CollectionUtils.stream(Arrays.asList(1,2,3)));
		Assert.assertEquals(3, CollectionUtils.stream(Arrays.asList(1,2,3)).count());
	}

	/**
	 * 判断集合是否为空
	 */
	@Test
	public void isEmpty() {
		Assert.assertTrue(CollectionUtils.isEmpty(null, Collections.emptyList(), null));
		Assert.assertFalse(CollectionUtils.isEmpty(null, Collections.emptyList(), Arrays.asList(1,2,3)));
	}

	/**
	 * 清除集合或map
	 */
	@Test
	public void clear() {
		List<Integer> list = new ArrayList<>(Arrays.asList(1,2,3,4,5));
		Set<Integer> set = new HashSet<>(Arrays.asList(1,2,3,4,5));
		Map<Integer, String> map = new HashMap<>();
		map.put(1, "1");
		map.put(2, "2");

		CollectionUtils.clear(null, list, set, map);

		Assert.assertThat(list, IsEmptyCollection.empty());
		Assert.assertThat(set, IsEmptyCollection.empty());
		Assert.assertTrue(map.isEmpty());
	}
}