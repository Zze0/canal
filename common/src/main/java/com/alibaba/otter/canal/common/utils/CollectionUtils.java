package com.alibaba.otter.canal.common.utils;

import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * 集合工具类
 * @author zhouzl
 * @since 2018年3月16日
 *
 */
public final class CollectionUtils extends org.apache.commons.collections.CollectionUtils {
	
	public static final Logger logger = LoggerFactory.getLogger(CollectionUtils.class);

	/**
	 * 类型转换 接口类
	 * @author zhouzl
	 * @since 2018年6月19日
	 *
	 * @param <T> 待转换的对象类型
	 */
	@FunctionalInterface
	public interface Transformer<T>{
		/**
		 * 接收对象并转换
		 * @author zhouzl
		 * @since 2018年6月19日
		 *
		 * @param t 待转换的对象
		 */
		void accept(T t);
	}

	/**
	 * 集合转换map 转换类
	 * @author zhouzl
	 * @since 2018年3月21日
	 *
	 * @param <T> 待转换的集合中的元素的类型
	 * @param <K> 转换后的map的key的类型
	 * @param <V> 转换后的map的value的类型
	 */
	public static class ToMapTransformer<T, K, V> implements Transformer<T> {
		/***获取key的函数*/
		private Function<T, K> keyMapper;
		/***获取value的函数*/
		private Function<T, V> valueMapper;
		/***实例化Map的函数*/
		private Supplier<Map<K, V>> mapSupplier;
		/***转换后的Map*/
		private Map<K, V> map;

		/**
		 * @param keyMapper 获取key的函数
		 * @param valueMapper 获取value的函数
		 * @param mapSupplier 实例化Map的函数（为null就默认实例化HashMap）
		 */
		public ToMapTransformer(
				Function<T, K> keyMapper, Function<T, V> valueMapper, Supplier<Map<K, V>> mapSupplier) {

			this.keyMapper = keyMapper;
			this.valueMapper = valueMapper;
			this.mapSupplier = null==mapSupplier ? HashMap::new : () -> {
				Map<K, V> newMap = mapSupplier.get();
				if (null==newMap){
					//保证实例化函数产出的map对象不为null
					newMap = new HashMap<>();
				}
				return newMap;
			};
		}


		/**
		 * 获取map的key
		 * @param t 待转换的对象
		 * @return
		 */
		public K getKey(T t){
			return null==keyMapper ? null : keyMapper.apply(t);
		}

		/**
		 * 获取map的value
		 * @param t 待转换的对象
		 * @return
		 */
		public V getValue(T t){
			return null==valueMapper ? null : valueMapper.apply(t);
		}

		public Map<K, V> getMap() {
			if (null==map){
				map = this.getMapSupplier().get();
			}
			return map;
		}

		@Override
		public void accept(T t) {
			K key = getKey(t);
			V value = getValue(t);

			getMap().put(key, value);
		}

		public Function<T, K> getKeyMapper() {
			return keyMapper;
		}

		public Function<T, V> getValueMapper() {
			return valueMapper;
		}

		public Supplier<Map<K, V>> getMapSupplier() {
			return mapSupplier;
		}
	}

	/**
	 * 集合转换成集合Map 转换类
	 * @author zhouzl
	 * @since 2018年3月21日
	 *
	 * @param <T> 待转换的集合中的元素的类型
	 * @param <K> 转换后的map的key的类型
	 * @param <V> 转换后的map的value集合类型
	 * @param <E> 转换后的map的value集合中的元素类型
	 */
	public static class ToCollectionMapTransformer<T, K, V extends Collection<E>, E> extends ToMapTransformer<T, K, V> {
		/***获取map的value集合中的元素的函数*/
		private Function<T, E> elementMapper;
		/***实例化集合的函数*/
		private Supplier<V> collectionSupplier;
		/***是否对List集合里面的元素进行去重*/
		private boolean distinctList;

		/**
		 * @param keyMapper   获取key的函数
		 * @param elementMapper 获取map的value集合中的元素的函数
		 * @param mapSupplier 实例化Map的函数（为null就默认实例化HashMap）
		 * @param collectionSupplier 实例化集合的函数
		 */
		public ToCollectionMapTransformer(
				Function<T, K> keyMapper, Function<T, E> elementMapper,
				Supplier<Map<K, V>> mapSupplier, Supplier<V> collectionSupplier) {

			this(keyMapper, elementMapper, mapSupplier, collectionSupplier, false);
		}

		/**
		 * @param keyMapper   获取key的函数
		 * @param elementMapper 获取map的value集合中的元素的函数
		 * @param mapSupplier 实例化Map的函数（为null就默认实例化HashMap）
		 * @param collectionSupplier 实例化集合的函数
		 * @param distinctList 是否对List集合里面的元素进行去重【当collectionSupplier实例化的是List时，本参数才有效果】
		 */
		public ToCollectionMapTransformer(
				Function<T, K> keyMapper, Function<T, E> elementMapper,
				Supplier<Map<K, V>> mapSupplier, Supplier<V> collectionSupplier,
				boolean distinctList) {

			super(keyMapper, null, mapSupplier);

			Assert.notNull(collectionSupplier, "collectionSupplier");

			this.elementMapper = elementMapper;
			this.collectionSupplier = collectionSupplier;
			this.distinctList = distinctList;
		}

		/**
		 * 获取map的value集合中的元素
		 * @param t 待转换的对象
		 * @return
		 */
		public E getElement(T t) {
			return null==elementMapper ? null : elementMapper.apply(t);
		}

		@Override
		public void accept(T t) {
			K key = getKey(t);
			E element = getElement(t);

			Map<K, V> map = getMap();

			V collection = map.get(key);

			if (null==collection){
				collection = collectionSupplier.get();
				Assert.notNull(collection, "collectionSupplier实例化的集合对象不能为null");
				map.put(key, collection);
			}

			if (distinctList
					&& collection instanceof List
					&& collection.contains(element)){
				//List去重
				return;
			}

			collection.add(element);
		}
	}

	/**
	 * 集合转换成嵌套Map 转换类
	 * @author zhouzl
	 * @since 2019年6月5日
	 *
	 * @param <T> 待转换的集合中的元素的类型
	 * @param <K> 转换后的外层map的key的类型
	 * @param <K2> 转换后的内层Map的key的类型
	 * @param <V2> 转换后的内层Map的value的类型
	 */
	public static class ToNestedMapTransformer<T, K, K2, V2> extends ToMapTransformer<T, K, Map<K2, V2>> {

		/***实例化内层map转换器的函数*/
		private Supplier<ToMapTransformer<T, K2, V2>> nestedMapTransformerSupplier;

		/**
		 * 外层map的key 和 内层map转换器的关联关系 (key是外层map的key，value是内层map转换器对象)
		 */
		private Map<K, ToMapTransformer<T, K2, V2>> nestedMapTransformerMap;

		/**
		 * @param keyMapper 获取外层map的key的函数
		 * @param nestedMapTransformerSupplier 实例化内层map转换器的函数
		 * @param mapSupplier 实例化Map的函数（为null就默认实例化HashMap）
		 */
		public ToNestedMapTransformer(
				Function<T, K> keyMapper,
				Supplier<ToMapTransformer<T, K2, V2>> nestedMapTransformerSupplier,
				Supplier<Map<K, Map<K2, V2>>> mapSupplier) {

			super(keyMapper, null, mapSupplier);
			Assert.notNull(nestedMapTransformerSupplier, "nestedMapTransformerSupplier");
			this.nestedMapTransformerSupplier = nestedMapTransformerSupplier;
		}

		@Override
		public void accept(T t) {
			//外层map的key
			K key = getKey(t);

			//内层map转换器
			ToMapTransformer<T, K2, V2> nestedMapTransformer = getNestedMapTransformerMap().get(key);

			if (null==nestedMapTransformer){
				nestedMapTransformer = nestedMapTransformerSupplier.get();
				Assert.notNull(nestedMapTransformer, "nestedMapTransformerSupplier实例化的转换器对象不能为null");

				//对外层map的key 和 内层map的转换器对象 建立关系
				getNestedMapTransformerMap().put(key, nestedMapTransformer);

				//外层map设置键值
				getMap().put(key, nestedMapTransformer.getMap());
			}

			//内层map转换器接受对象，进行转换处理
			nestedMapTransformer.accept(t);
		}

		private Map<K, ToMapTransformer<T, K2, V2>> getNestedMapTransformerMap() {
			if (null==nestedMapTransformerMap){
				nestedMapTransformerMap = new HashMap<>();

			}
			return nestedMapTransformerMap;
		}
	}

	/**
	 * 获取分页最大页数
	 * @author zhouzl
	 * @since 2018年3月16日
	 *
	 * @param list 数据集合
	 * @param pageSize 每页最多显示多少记录
	 * @return
	 */
	public static final int getMaxPageIndex(List<?> list, int pageSize){
		if (isEmpty(list)) {
			return 0;
		}
		int maxPage = list.size() / pageSize;
		//如果求余大于0，说明还有些尾数，也算一页。
		if (list.size() % pageSize > 0) {
			maxPage++;
		}
		return maxPage;
	}
	
	/**
	 * 获取指定页的数据列表
	 * @author zhouzl
	 * @since 2018年3月16日
	 *
	 * @param list 数据集合
	 * @param pageSize 每页最多显示多少记录
	 * @param pageIndex 当前页数
	 * @param maxPage 分页最大页数（不传就自动计算最大页数）
	 * @return
	 */
	public static final <T> List<T> getPage(List<T> list, int pageSize, int pageIndex, Integer maxPage){
		if (isEmpty(list)) {
			return Collections.emptyList();
		}
		Integer resultMaxPage = maxPage;
		if (resultMaxPage==null) {
			resultMaxPage = getMaxPageIndex(list, pageSize);
		}
		if (pageIndex>0 && resultMaxPage>=pageIndex) {
			int fromIndex = (pageIndex-1) * pageSize;
			int toIndex = (pageIndex==resultMaxPage) ? list.size() : fromIndex+pageSize;
			
			return list.subList(fromIndex, toIndex);
		}
		
		return Collections.emptyList();
	}
	
	/**
	 * 分页式遍历list
	 * @author zhouzl
	 * @since 2018年3月16日
	 * 
	 * @param consumer 消费者
	 * @param list 数据集合
	 * @param pageSize 每页最多显示多少记录 
	 */
	public static final <T> void forEachPage(Consumer<List<T>> consumer, List<T> list, int pageSize){
		forEachPage(consumer, list, pageSize, false);
	}

	/**
	 * 分页式遍历list
	 * @author zhouzl
	 * @since 2018年3月16日
	 * 
	 * @param consumer 消费者
	 * @param list 数据集合
	 * @param pageSize 每页最多显示多少记录 
	 * @param atLeastOnce 是否至少执行一次 消费者（如果为true，就表示忽略list为空的情况，会至少执行一次）
	 */
	public static final <T> void forEachPage(
			Consumer<List<T>> consumer, List<T> list, 
			int pageSize, boolean atLeastOnce){

		if (null==consumer){
			return;
		}

		if (isEmpty(list)){
			//至少执行一次
			if (atLeastOnce){
				consumer.accept(list);
			}
			return;
		}

		//分页最大页数
		int maxPageIndex = getMaxPageIndex(list, pageSize);

		//当前页数
		int pageIndex = 0;
		while ((++pageIndex) <= maxPageIndex) {
			consumer.accept(getPage(list, pageSize, pageIndex, maxPageIndex));
		}
	}
	
	/**
	 * 转换Map <p/>
	 * 注：Collectors.toMap用法可能会有重复key异常和value空指针的异常，所以这里工具类自实现了一套转map的方法。<p/>
	 * 
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,V> toMap(Collection<T> collection, Function<T,K> keyMapper,Function<T,V> valueMapper){
		ToMapTransformer<T, K, V> toMapTransformer = new ToMapTransformer<>(keyMapper, valueMapper, null);
		transform(collection, toMapTransformer);
		return toMapTransformer.getMap();
	}

	/**
	 * 转换Map <p/>
	 * 注：Collectors.toMap用法可能会有重复key异常和value空指针的异常，所以这里工具类自实现了一套转map的方法。<p/>
	 *
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param filter 过滤器，返回false表示转换时忽略掉这个元素(为null时不过滤
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,V> toMap(Collection<T> collection, Predicate<T> filter, Function<T,K> keyMapper,Function<T,V> valueMapper){
		ToMapTransformer<T, K, V> toMapTransformer = new ToMapTransformer<>(keyMapper, valueMapper, null);
		transform(collection, filter, toMapTransformer);
		return toMapTransformer.getMap();
	}

	/**
	 * 转换Map <p/>
	 * 注：Collectors.toMap用法可能会有重复key异常和value空指针的异常，所以这里工具类自实现了一套转map的方法。<p/>
	 *
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,V> toLinkedMap(Collection<T> collection, Function<T,K> keyMapper,Function<T,V> valueMapper){
		ToMapTransformer<T, K, V> toMapTransformer = new ToMapTransformer<>(keyMapper, valueMapper, LinkedHashMap::new);
		transform(collection, toMapTransformer);
		return toMapTransformer.getMap();
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的list集合）
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value集合里面的值的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,List<V>> toListMap(Collection<T> collection, Function<T,K> keyMapper,Function<T,V> valueMapper){
		return toListMap(collection, null, keyMapper, valueMapper);
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的list集合）
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param filter 过滤器，返回false表示转换时忽略掉这个元素(为null时不过滤
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value集合里面的值的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,List<V>> toListMap(Collection<T> collection, Predicate<T> filter, Function<T,K> keyMapper,Function<T,V> valueMapper){
		return toListMap(collection, filter, false, keyMapper, valueMapper);
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的list集合）
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param filter 过滤器，返回false表示转换时忽略掉这个元素(为null时不过滤
	 * @param distinctValue 是否对每个集合进行去重
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value集合里面的值的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,List<V>> toListMap(
			Collection<T> collection, Predicate<T> filter, boolean distinctValue, Function<T,K> keyMapper,Function<T,V> valueMapper){

		ToCollectionMapTransformer<T, K, List<V>, V> toListMapTransformer = new ToCollectionMapTransformer<>(keyMapper, valueMapper, null, ArrayList::new, distinctValue);
		transform(collection, filter, toListMapTransformer);
		return toListMapTransformer.getMap();
	}

	/**
	 * 转换有序的集合Map（key是指定属性值，value是指定属性值的list集合）
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value集合里面的值的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,List<V>> toLinkedListMap(Collection<T> collection, Function<T,K> keyMapper,Function<T,V> valueMapper){
		return toLinkedListMap(collection, null, keyMapper, valueMapper);
	}

	/**
	 * 转换有序的集合Map（key是指定属性值，value是指定属性值的list集合）
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param filter 过滤器，返回false表示转换时忽略掉这个元素(为null时不过滤
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value集合里面的值的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,List<V>> toLinkedListMap(Collection<T> collection, Predicate<T> filter, Function<T,K> keyMapper,Function<T,V> valueMapper){
		ToCollectionMapTransformer<T, K, List<V>, V> toListMapTransformer = new ToCollectionMapTransformer<>(keyMapper, valueMapper, LinkedHashMap::new, ArrayList::new);
		transform(collection, filter, toListMapTransformer);
		return toListMapTransformer.getMap();
	}

	/**
	 * 转换集合Map（key是指定属性值，value是指定属性值的set集合）
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取key的函数
	 * @param valueMapper 获取value集合里面的值的函数
	 * @return
	 */
	public static final <T,K,V> Map<K,Set<V>> toSetMap(Collection<T> collection, Function<T,K> keyMapper,Function<T,V> valueMapper){
		ToCollectionMapTransformer<T, K, Set<V>, V> toSetMapTransformer = new ToCollectionMapTransformer<>(keyMapper, valueMapper, null, HashSet::new);
		transform(collection, toSetMapTransformer);
		return toSetMapTransformer.getMap();
	}

	/**
	 * 转换嵌套Map（key是指定属性值，value是一个内层Map ）
	 * 例子如： Map< String, Map< String, Object > >
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取外层Map的key的函数
	 * @param nestedKeyMapper 获取内层Map的key的函数
	 * @param nestedValueMapper 获取内层Map的value集合的元素的函数
	 * @return
	 */
	public static final <T, K, K2, V2> Map<K, Map<K2, V2>> toNestedMap(
			Collection<T> collection, Function<T, K> keyMapper,
			Function<T, K2> nestedKeyMapper, Function<T, V2> nestedValueMapper) {

		ToNestedMapTransformer<T, K, K2, V2> transformer =
				new ToNestedMapTransformer<>(
						keyMapper,
						() -> new ToMapTransformer<>(nestedKeyMapper, nestedValueMapper, null),
						null);

		transform(collection, transformer);

		return transformer.getMap();
	}

	/**
	 * 转换嵌套的集合Map（key是指定属性值，value是一个内层Map（key是指定属性值，value是指定属性值的List集合） ）
	 * 例子如： Map< String, Map< String, List< Object > > >
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取外层Map的key的函数
	 * @param nestedKeyMapper 获取内层Map的key的函数
	 * @param nestedValueMapper 获取内层Map的value集合的元素的函数
	 * @return
	 */
	public static final <T, K, K2, V2> Map<K, Map<K2, List<V2>>> toNestedListMap(
			Collection<T> collection, Function<T, K> keyMapper,
			Function<T, K2> nestedKeyMapper, Function<T, V2> nestedValueMapper) {

		ToNestedMapTransformer<T, K, K2, List<V2>> transformer =
				new ToNestedMapTransformer<>(
						keyMapper,
						() -> new ToCollectionMapTransformer<>(nestedKeyMapper, nestedValueMapper, null, ArrayList::new),
						null);

		transform(collection, transformer);

		return transformer.getMap();
	}

	/**
	 * 转换嵌套的集合Map（key是指定属性值，value是一个内层Map（key是指定属性值，value是指定属性值的Set集合） ）
	 * 例子如： Map< String, Map< String, Set< Object > > >
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param collection 待转换的集合
	 * @param keyMapper 获取外层Map的key的函数
	 * @param nestedKeyMapper 获取内层Map的key的函数
	 * @param nestedValueMapper 获取内层Map的value集合的元素的函数
	 * @return
	 */
	public static final <T, K, K2, V2> Map<K, Map<K2, Set<V2>>> toNestedSetMap(
			Collection<T> collection, Function<T, K> keyMapper,
			Function<T, K2> nestedKeyMapper, Function<T, V2> nestedValueMapper) {

		ToNestedMapTransformer<T, K, K2, Set<V2>> transformer =
				new ToNestedMapTransformer<>(
						keyMapper,
						() -> new ToCollectionMapTransformer<>(nestedKeyMapper, nestedValueMapper, null, HashSet::new),
						null);

		transform(collection, transformer);

		return transformer.getMap();
	}

	/**
	 * 批量转换
	 * @author zhouzl
	 * @since 2018年6月19日
	 *
	 * @param collection 待转换的集合
	 * @param transformers 转换类对象（可多个）
	 */
	@SafeVarargs
	public static final <T> void transform(Collection<T> collection, Transformer<T>... transformers){
		transform(collection, null, transformers);
	}

	/**
	 * 批量转换
	 * @author zhouzl
	 * @since 2018年6月19日
	 *
	 * @param collection 待转换的集合
	 * @param filter 过滤器，返回false表示转换时忽略掉这个元素(为null时不过滤
	 * @param transformers 转换类对象（可多个）
	 */
	@SafeVarargs
	public static final <T> void transform(Collection<T> collection, Predicate<T> filter, Transformer<T>... transformers){
		if (ArrayUtils.isNotEmpty(transformers) && isNotEmpty(collection)) {
			collection.forEach(element -> {
				//是否通过过滤器
				boolean isPass = null == filter || filter.test(element);

				if (isPass){
					for (Transformer<T> transformer : transformers) {
						transformer.accept(element);
					}
				}
			});
		}
	}

	/**
	 * 追加元素到集合
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param list 集合（如果是null，就实例化一个）
	 * @param element 元素
	 * @return
	 */
	public static final <T> List<T> addToList(List<T> list, T element){
		List<T> resultList = list==null ? new ArrayList<>() : list;
		resultList.add(element);
		return resultList;
	}
	
	/**
	 * 追加元素到集合
	 * @author zhouzl
	 * @since 2018年3月20日
	 *
	 * @param set 集合（如果是null，就实例化一个）
	 * @param element 元素
	 * @return
	 */
	public static final <T> Set<T> addToSet(Set<T> set, T element){
		Set<T> resultSet = set==null ? new HashSet<>() : set;
		resultSet.add(element);
		return resultSet;
	}
	
	/**
	 * 批量消费多个集合
	 * @author zhouzl
	 * @since 2018年3月24日
	 *
	 * @param consumer 元素消费者
	 * @param lists 多个集合
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static final void forEachMultiple(Consumer consumer, List... lists){
		if (ArrayUtils.isNotEmpty(lists)) {
			for (List<?> list : lists) {
				if (isNotEmpty(list)) {
					list.forEach(consumer);
				}
			}
		}
	}
	
	/**
	 * 集合去重（保留第1次出现的元素）
	 * @author zhouzl
	 * @since 2018年5月28日
	 *
	 * @param collection
	 * @return
	 */
	public static final <T> List<T> distinct(Collection<T> collection){
		if (isNotEmpty(collection)) {
			return collection.stream().distinct().collect(Collectors.toList());
		}
		return Collections.emptyList();
	}

	/**
	 * 集合反向去重（保留最后1次出现的元素）
	 * @author zhouzl
	 * @since 2018年5月28日
	 *
	 * @param collection
	 * @return
	 */
	public static final <T> List<T> distinctReversed(Collection<T> collection){
		if (isNotEmpty(collection)) {
			List<T> reverseList = new ArrayList<>(collection);
			//先反向排序
			Collections.reverse(reverseList);

			//执行正常去重，保留集合第1次出现的元素
			reverseList = distinct(reverseList);

			//最终再次反向排序，返回原来的顺序。就实现了保留集合最后1次出现的元素
			Collections.reverse(reverseList);
			return reverseList;
		}
		return Collections.emptyList();
	}

	/**
	 * 追加其他集合的元素到 指定集合
	 * @author zhouzl
	 * @since 2018年5月31日
	 *
	 * @param collection 需要被追加元素的集合
	 * @param iters	其他集合
	 */
	@SafeVarargs
	public static final <T> void addAll(Collection<T> collection, Iterable<T>... iters){
		addAll(collection, null, iters);
	}

	/**
	 * 追加其他集合的元素到 指定集合
	 * @author zhouzl
	 * @since 2018年5月31日
	 *
	 * @param collection 需要被追加元素的集合
	 * @param handler 元素处理器，在追加到指定集合前可以做些特殊的处理
	 * @param iters	其他集合
	 */
	@SafeVarargs
	public static final <T> void addAll(Collection<T> collection, Function<T, T> handler, Iterable<T>... iters){
		addAll(collection, handler, null, iters);
	}

	/**
	 * 追加其他集合的元素到 指定集合
	 * @author zhouzl
	 * @since 2018年5月31日
	 *
	 * @param collection 需要被追加元素的集合
	 * @param handler 元素处理器，在追加到指定集合前可以做些特殊的处理
	 * @param filter 元素过滤器，过滤一下不需要处理的元素
	 * @param iters	其他集合
	 */
	@SafeVarargs
	public static final <T> void addAll(Collection<T> collection, Function<T, T> handler, Predicate<T> filter, Iterable<T>... iters){
		if (collection!=null && ArrayUtils.isNotEmpty(iters)) {
			Stream.of(iters).filter(Objects::nonNull).forEach(iter -> iter.forEach(element -> {
				boolean canAdd = null==filter || filter.test(element);

				if (canAdd) {
					element = handler!=null ? handler.apply(element) : element;
					collection.add(element);
				}
			}));
		}
	}

	/**
	 * 从集合1中扣除集合2中相符的元素
	 * @param list1 集合1
	 * @param list2 集合2
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <E> List<E> subtract(final List<E> list1, final List<? extends E> list2) {

		List<E> resultList;

		if (isEmpty(list2)){
			resultList = list1;
		}
		else if (isEmpty(list1)){
			resultList = Collections.emptyList();
		}
		else {
			resultList = ListUtils.subtract(distinct(list1), distinct(list2));
		}

		return null==resultList ? Collections.emptyList() : new ArrayList<>(resultList);
	}

	/**
	 * 合并两个集合
	 * @param list1 集合1
	 * @param list2 集合2
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <E> List<E> union(List<E> list1, List<E> list2) {

		List<E> resultList;

		if (isEmpty(list1)){
			resultList = list2;
		}
		else if (isEmpty(list2)){
			resultList = list1;
		}
		else {
			resultList = ListUtils.sum(list1, list2);
		}

		return null==resultList ? Collections.emptyList() : distinct(resultList);
	}

	/**
	 * 字符串拼接
	 * @param list 对象集合
	 * @param separator 字符串分隔符
	 * @return 如果集合为空就返回空字符串
	 */
	public static <T> String joining(List<T> list, String separator){
		return joining(list, null, null, separator);
	}

	/**
	 * 字符串拼接
	 * @param list 对象集合
	 * @param getter 对象转字符串的函数（如果为null，就直接默认调用Object.toString()）
	 * @param separator 字符串分隔符
	 * @return 如果集合为空就返回空字符串
	 */
	public static <T> String joining(List<T> list, Function<T,String> getter, String separator){
		return joining(list, null, getter, separator);
	}

	/**
	 * 字符串拼接
	 * @param list 对象集合
	 * @param filter 过滤器
	 * @param getter 对象转字符串的函数（如果为null，就直接默认调用Object.toString()）
	 * @param separator 字符串分隔符
	 * @return 如果集合为空就返回空字符串
	 */
	public static <T> String joining(List<T> list, Predicate<T> filter, Function<T,String> getter, String separator){
		if (isEmpty(list)){
			return "";
		}

		Function<T,String> realGetter = null==getter ? (element -> Objects.toString(element, null)) : getter;

		Stream<T> stream = list.stream();
		if (null != filter){
			stream = stream.filter(filter);
		}

		return stream.map(realGetter).filter(StringUtils::isNotBlank).collect(Collectors.joining(separator));
	}

	/**
	 * 分割字符串成集合
	 * @param str 字符串
	 * @param separator 分隔符
	 */
	public static List<String> splitString(String str, String separator){
		if (StringUtils.isBlank(str) || StringUtils.isBlank(separator)){
			return Collections.emptyList();
		}

		return Stream.of(str.split(separator)).filter(StringUtils::isNotEmpty).collect(Collectors.toList());
	}

	/**
	 * 分割字符串，然后进行循环操作
	 * @param str 字符串
	 * @param separator 分隔符
	 * @param consumer 循环内部处理函数
	 */
	public static void splitStringToForEach(String str, String separator, Consumer<String> consumer){
		if (StringUtils.isBlank(str) || StringUtils.isBlank(separator) || null==consumer){
			return;
		}

		String[] strArr = str.split(separator);

		for (String strForEach : strArr) {
			consumer.accept(strForEach);
		}
	}

	/**
	 * 消费集合
	 * @author zhouzl
	 * @since 2018年3月24日
	 *
	 * @param consumer 元素消费者
	 * @param list 集合
	 */
	public static <T> void forEach(Consumer<T> consumer, List<T> list){
		if (null==consumer || isEmpty(list)){
			return;
		}

		list.forEach(consumer);
	}

	/**
	 * 消费集合
	 * @author zhouzl
	 * @since 2018年3月24日
	 *
	 * @param consumer 元素消费者(第一个参数是元素对象，第二个参数是下标值)
	 * @param list 集合
	 */
	public static <T> void forEachWithIndex(BiConsumer<T, Integer> consumer, List<T> list){
		if (null==consumer || isEmpty(list)){
			return;
		}

		for (int index = 0; index < list.size(); index++) {
			consumer.accept(list.get(index), index);
		}
	}

	/**
	 * 获取对象长度（如Map、Collection、Iterable、数组、Iterator、Enumeration等等）
	 * @param object Map、Collection、Iterable、数组、Iterator、Enumeration等
	 * @return
	 */
	public static int size(Object object) {
		if (null==object){
			return 0;
		}

		return org.apache.commons.collections.CollectionUtils.size(object);
	}

	/**
	 * 集合流化
	 * @param collection 集合（为null时，会返回空的流）
	 * @return
	 */
	public static <T> Stream<T> stream(Collection<T> collection){
		if (null==collection){
			return Stream.empty();
		}
		return collection.stream();
	}

	/**
	 * 判断集合是否为空
	 * @param collections 集合
	 * @return
	 */
	public static boolean isEmpty(Collection<?>... collections){

		if (ArrayUtils.isNotEmpty(collections)) {
			for (Collection<?> collection : collections) {
				if (isNotEmpty(collection)) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * 清除集合或map
	 * @param objs 集合或map
	 */
	public static void clear(Object... objs) {
		if (ArrayUtils.isNotEmpty(objs)) {
			for (Object obj : objs) {
				if (obj instanceof Collection) {
					((Collection) obj).clear();
				} else if (obj instanceof Map) {
					((Map) obj).clear();
				}
			}
		}
	}
}
