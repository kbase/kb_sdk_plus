package us.kbase.kidl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Utils {

	public static String prop(Map<?,?> map, String propName) throws KidlParseException {
		return propAbstract(map, propName, String.class);
	}

	public static String propOrNull(Map<?,?> map, String propName) throws KidlParseException {
		if (map.get(propName) == null)
			return null;
		return prop(map, propName);
	}

	public static List<?> propList(Map<?,?> map, String propName) throws KidlParseException {
		return propAbstract(map, propName, List.class);
	}

	public static Map<?,?> propMap(Map<?,?> map, String propName) throws KidlParseException {
		return propAbstract(map, propName, Map.class);
	}
	
	@SuppressWarnings("unchecked")
	private static <T> T propAbstract(Map<?,?> map, String propName, Class<T> returnType) throws KidlParseException {
		if (!map.containsKey(propName))
			throw new KidlParseException("No property in the map: " + propName);
		Object ret = map.get(propName);
		if (ret == null)
			throw new KidlParseException("No property in the map: " + propName);
		if (returnType != null && !returnType.isInstance(ret))
			throw new KidlParseException("Value for property [" + propName + "] is not compatible " +
					"with type [" + returnType.getName() + "], it has type: " + ret.getClass().getName());
		return (T)ret;
	}

	public static List<String> repareTypingString(List<?> list) throws KidlParseException {
		return repareTypingAbstract(list, String.class);
	}

	@SuppressWarnings("rawtypes")
	public static List<Map> repareTypingMap(List<?> list) throws KidlParseException {
		return repareTypingAbstract(list, Map.class);
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> repareTypingAbstract(List<?> list, Class<T> itemType) throws KidlParseException {
		List<T> ret = new ArrayList<T>();
		for (Object item : list) {
			if (!itemType.isInstance(item))
				throw new KidlParseException("List item is not compatible with type " +
						"[" + itemType.getName() + "], it has type: " + item.getClass().getName());
			ret.add((T)item);
		}
		return ret;
	}

	@SuppressWarnings("rawtypes")
	public static List<Map> getListOfMapProp(Map<?,?> data, String propName) throws KidlParseException {
		return Utils.repareTypingMap(Utils.propList(data, propName));
	}
	
	public static String getPerlSimpleType(Map<?,?> map) throws KidlParseException {
		return getPerlSimpleType(prop(map, "!"));
	}

	private static String getPerlSimpleType(String type) {
		return type.contains("::") ? type.substring(type.lastIndexOf("::") + 2) : type;
	}

	public static KbType createTypeFromMap(Map<?,?> data) throws KidlParseException {
		return createTypeFromMap(data, null);
	}
	
	public static KbType createTypeFromMap(Map<?,?> data, KbAnnotations annFromTypeDef) throws KidlParseException {
		String typeType = Utils.getPerlSimpleType(data);
		KbType ret = typeType.equals("Typedef") ? new KbTypedef().loadFromMap(data) :
			KbBasicType.createFromMap(data, annFromTypeDef);
		return ret;
	}
	
	public static int intPropFromString(Map<?,?> map, String propName) throws KidlParseException {
		String value = prop(map, propName);
		try {
			return Integer.parseInt(value);
		} catch(Exception ex) {
			throw new KidlParseException("Value for property [" + propName + "] is not integer: " + value);
		}
	}

	public  static KbType resolveTypedefs(KbType type) {
		if (type instanceof KbTypedef) 
			return resolveTypedefs(((KbTypedef)type).getAliasType());
		return type;
	}
}
