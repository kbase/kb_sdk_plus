package us.kbase.sdk.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import us.kbase.kidl.KbAuthdef;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbList;
import us.kbase.kidl.KbMapping;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbParameter;
import us.kbase.kidl.KbScalar;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KbUnspecifiedObject;
import us.kbase.kidl.KidlNode;
import us.kbase.kidl.KidlVisitor;
import us.kbase.sdk.compiler.Utils.NameAndTypeAlias;

/**
 * Transform a KIDL class structure to a data structure for template generation by the
 * {@link TemplateBasedGenerator}.
 */
public class TemplateVisitor implements KidlVisitor<Map<String, Object>> {
	
	// TODO CODE this was refactored from methods on all the classes, but it doesn't really
	//           fit the visitor pattern. For the first pass I wanted to keep things close
	//           to the original code so the changes are easy to understand.
	//           Later see if it can be made more visitor-like... or not. Maybe not worth the time.
	
	
	private static final int PYDOC_STRING_WIDTH = 70;
	private static final String PYDOC_STRING_INDENT = "   ";

	@Override
	public Map<String, Object> visit(final KbAuthdef auth) {
		return null;
	}

	@Override
	public Map<String, Object> visit(
			final KbFuncdef func,
			final List<Map<String, Object>> incparams,
			final List<Map<String, Object>> outreturns
			) {
		final List<KbParameter> parameters = func.getParameters();
		final List<KbParameter> returnType = func.getReturnType();
		final String authentication = func.getAuthentication();
		Map<String, Object> ret = new LinkedHashMap<String, Object>();
		ret.put("name", func.getName());
		ret.put("arg_count", parameters.size());
		List<String> paramNames = getNameList(parameters, false);
		ret.put("args", getNames(paramNames, null));
		ret.put("arg_vars", getNames(paramNames, "$"));
		ret.put("ret_count", returnType.size());
		List<String> returnNames = getNameList(returnType, true);
		ret.put("ret_vars", getNames(returnNames, "$"));
		ret.put("authentication", authentication == null ? "none" : authentication);
		List<String> docLines = new ArrayList<String>();
		final LinkedList<NameAndTypeAlias> typeQueue = new LinkedList<NameAndTypeAlias>();
		for (int paramPos = 0; paramPos < parameters.size(); paramPos++) {
			KbParameter arg = parameters.get(paramPos);
			String item = paramNames.get(paramPos);
			typeQueue.add(new NameAndTypeAlias("$" + item, arg.getType()));
		}
		for (int returnPos = 0; returnPos < returnType.size(); returnPos++) {
			KbParameter arg = returnType.get(returnPos);
			String item = returnNames.get(returnPos);
			typeQueue.add(new NameAndTypeAlias("$" + item, arg.getType()));
		}
		processArgDoc(typeQueue, docLines, null, true);
		ret.put("arg_doc", docLines);
		String docWithoutStars = Utils.removeStarsInComment(func.getComment());
		ret.put("doc", docWithoutStars);
		List<String> pyDocLines = new ArrayList<String>();
		for (String line : Utils.parseCommentLines(docWithoutStars)) {
			pyDocLines.add(removeThreeQuotes(line));
		}
		for (int paramPos = 0; paramPos < parameters.size(); paramPos++) {
			KbParameter arg = parameters.get(paramPos);
			String paramName = paramNames.get(paramPos);
			String descr = getTypeDescr(arg.getType(), null);
			pyDocLines.addAll(cutLine(":param " + paramName + ": " + removeThreeQuotes(descr),
					PYDOC_STRING_WIDTH, PYDOC_STRING_INDENT));
		}
		if (returnType.size() > 0) {
			String descr;
			if (returnType.size() == 1) {
				KbParameter arg = returnType.get(0);
				descr = getTypeDescr(arg.getType(), null);
			} else {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < returnType.size(); i++) {
					KbParameter arg = returnType.get(i);
					if (sb.length() > 0)
						sb.append(", ");
					sb.append('(').append((i + 1)).append(") ");
					sb.append(getTypeDescr(arg.getType(), arg.getName()));
				}
				descr = "multiple set - " + sb.toString();
			}
			pyDocLines.addAll(cutLine(":returns: " + removeThreeQuotes(descr), 
					PYDOC_STRING_WIDTH, PYDOC_STRING_INDENT));
		}
		ret.put("py_doc_lines", pyDocLines);
		List<Object> params = new ArrayList<Object>();
		for (int paramPos = 0; paramPos < parameters.size(); paramPos++) {
			KbParameter param = parameters.get(paramPos);
			Map<String, Object> paramMap = getParamMap(param, paramNames.get(paramPos));
			paramMap.put("index", paramPos + 1);
			params.add(paramMap);
		}
		ret.put("params", params);
		List<Object> returns = new ArrayList<Object>();
		for (int retPos = 0; retPos < returnType.size(); retPos++) {
			KbParameter retParam = returnType.get(retPos);
			Map<String, Object> paramMap = getParamMap(retParam, returnNames.get(retPos));
			paramMap.put("index", retPos + 1);
			returns.add(paramMap);
		}
		ret.put("returns", returns);
		return ret;
	}

	private static String removeThreeQuotes(String line) {
		return line.replaceAll("\"{3,}", "\"\"");
	}

	private static List<String> cutLine(String line, int width, String indent) {
		if (indent.length() >= width)
			throw new IllegalStateException("Indent width is larger than " + width);
		line = line.trim();
		List<String> ret = new ArrayList<String>();
		String curLine = line;
		boolean firstLine = true;
		while (curLine.length() > 0) {
			int curWidth = firstLine ? width : (width - indent.length());
			int pos = curLine.length() <= curWidth ? curLine.length() :
				curLine.substring(0, curWidth).lastIndexOf(' ');
			if (pos < 0)
				pos = curWidth;
			ret.add((firstLine ? "" : indent) + curLine.substring(0, pos));
			curLine = curLine.substring(pos).trim();
			firstLine = false;
		}
		return ret;
	}

	private static void processArgDoc(LinkedList<NameAndTypeAlias> typeQueue, 
			List<String> docLines, Set<String> allKeys, boolean topLevel) {
		if (allKeys == null)
			allKeys = new HashSet<String>();
		List<String> additional = new ArrayList<>();
		final LinkedList<NameAndTypeAlias> subQueue = new LinkedList<NameAndTypeAlias>();
		while (!typeQueue.isEmpty()) {
			final NameAndTypeAlias namedType = typeQueue.removeFirst();
			String key = namedType.getName();
			if (allKeys.contains(key)) {
				continue;
			}
			allKeys.add(key);
			KbType type = namedType.getAliasType();
			additional.clear();
			String argLine = key + " is " + 
					Utils.getEnglishTypeDescr(type, subQueue, allKeys, additional);
			if (additional.size() > 0)
				argLine += ":";
			docLines.add(argLine);
			for (String add : additional)
				if (add.isEmpty()) {
					docLines.add("");
				} else {
					docLines.add("\t" + add);
				}
			if (subQueue.size() > 0 && !topLevel) {
				processArgDoc(subQueue, docLines, allKeys, false);
				if (subQueue.size() > 0)
					throw new IllegalStateException("Not empty: " + subQueue);
			}
		}
		if (subQueue.size() > 0)
			processArgDoc(subQueue, docLines, allKeys, false);
	}

	private static List<String> getNameList(List<KbParameter> args, boolean returned) {
		List<String> ret = new ArrayList<String>();
		for (int i = 0; i < args.size(); i++) {
			KbParameter arg = args.get(i);
			String item = arg.getOriginalName();
			if (item == null) {
				if (returned) {
					item = "return" + (args.size() > 1 ? ("_" + (i + 1)) : "");
				} else {
					KbType type = arg.getType();
					if (type instanceof KbTypedef) {
						item = ((KbTypedef)type).getName();
					} else {
						item = "arg_" + (i + 1);
					}
				}
			}
			ret.add(item);
		}
		Map<String, int[]> valToCount = new HashMap<String, int[]>();
		for (String val : ret) {
			int[] count = valToCount.get(val);
			if (count == null) {
				valToCount.put(val, new int[] {1, 0});
			} else {
				count[0]++;
			}
		}
		for (int pos = 0; pos < ret.size(); pos++) {
			String val = ret.get(pos);
			int[] count = valToCount.get(val);
			if (count[0] > 1) {
				val += "_" + (++count[1]);
				ret.set(pos, val);
			}
		}
		return ret;
	}

	private static String getNames(List<String> items, String prefix) {
		StringBuilder ret = new StringBuilder();
		for (String arg : items) {
			if (ret.length() > 0)
				ret.append(", ");
			if (prefix != null)
				ret.append(prefix);
			ret.append(arg);
		}
		return ret.toString();
	}

	private static String getTypeDescr(KbType type, String paramName) {
		StringBuilder sb = new StringBuilder();
		if (paramName == null)
			sb.append("instance of ");
		createTypeDescr(type, paramName, sb);
		return sb.toString();
	}

	private static void createTypeDescr(KbType type, String paramName, StringBuilder sb) {
		if (paramName != null)
			sb.append("parameter \"").append(paramName).append("\" of ");
		if (type instanceof KbTypedef) {
			KbTypedef ref = (KbTypedef)type;
			sb.append("type \"").append(ref.getName()).append("\"");
			List<String> refCommentLines = Utils.parseCommentLines(ref.getComment());
			if (refCommentLines.size() > 0) {
				StringBuilder concatLines = new StringBuilder();
				for (String l : refCommentLines) {
					if (concatLines.length() > 0 
							&& concatLines.charAt(concatLines.length() - 1) != ' ') {
						concatLines.append(' ');
					}
					concatLines.append(l.trim());
				}
				sb.append(" (").append(concatLines).append(")");
			}
			if (!(ref.getAliasType() instanceof KbScalar)) {
				sb.append(" -> ");
				createTypeDescr(ref.getAliasType(), null, sb);
			}
		} else if (type instanceof KbStruct) {
			KbStruct struct = (KbStruct)type;
			sb.append("structure: ");
			for (int i = 0; i < struct.getItems().size(); i++) {
				if (i > 0)
					sb.append(", ");
				String itemName = struct.getItems().get(i).getName();
				createTypeDescr(struct.getItems().get(i).getItemType(), itemName, sb);
			}
		} else if (type instanceof KbList) {
			KbList list = (KbList)type;
			sb.append("list of ");
			createTypeDescr(list.getElementType(), null, sb);
		} else if (type instanceof KbMapping) {
			KbMapping map = (KbMapping)type;
			sb.append("mapping from ");
			createTypeDescr(map.getKeyType(), null, sb);
			sb.append(" to ");
			createTypeDescr(map.getValueType(), null, sb);
		} else if (type instanceof KbTuple) {
			KbTuple tuple = (KbTuple)type;
			sb.append("tuple of size ").append(tuple.getElementTypes().size()).append(": ");
			for (int i = 0; i < tuple.getElementTypes().size(); i++) {
				if (i > 0)
					sb.append(", ");
				String tupleParamName = tuple.getElementNames().get(i);
				if (tupleParamName.equals("e_" + (i + 1)))
					tupleParamName = null;
				createTypeDescr(tuple.getElementTypes().get(i), tupleParamName, sb);
			}
		} else if (type instanceof KbUnspecifiedObject) {
			sb.append("unspecified object");
		} else if (type instanceof KbScalar) {
			sb.append(((KbScalar)type).getJavaStyleName());
		}
	}

	@Override
	public Map<String, Object> visit(final KbList list, final Map<String, Object> elementType) {
		return null;
	}

	@Override
	public Map<String, Object> visit(final KbMapping map, final Map<String, Object> keyType,
			final Map<String, Object> valueType) {
		return null;
	}

	@Override
	public Map<String, Object> visit(
			final KbModule module,
			final List<Map<String, Object>> components,
			final Map<String, Map<String, Object>> typeMap
			) {
		Map<String, Object> ret = new LinkedHashMap<String, Object>();
		ret.put("module_name", module.getModuleName());
		ret.put("module_doc", Utils.removeStarsInComment(module.getComment()));
		List<Object> methods = new ArrayList<Object>();
		List<Object> types = new ArrayList<Object>();
		for (KbModuleComp comp : module.getModuleComponents()) {
			// this is not really how a visitor is supposed to work, but for this refactor
			// I'm keeping the code as close to possible to the original
			if (comp instanceof KbFuncdef) {
				methods.add(((KbFuncdef)comp).accept(new TemplateVisitor(), null));
			} else if (comp instanceof KbTypedef) {
				types.add(((KbTypedef)comp).accept(new TemplateVisitor(), null));
			} else if (comp instanceof KbAuthdef) {
				//do nothing
			} else {
				System.out.println("Module component: " + comp);
			}
		}
		ret.put("methods", methods);
		ret.put("types", types);
		return ret;
	}

	@Override
	public Map<String, Object> visit(final KbParameter param, final Map<String, Object> type) {
		return null;
	}
	
	// TODO CODE altname prevents this from being a visitor fn
	private Map<String, Object> getParamMap(final KbParameter param, final String altName) {
		Map<String, Object> ret = new LinkedHashMap<String, Object>();
		String name = param.getOriginalName() != null ? param.getOriginalName() : altName;
		ret.put("name", name);
		String validator = null;  // TODO CODE this looks perl specific
		KbType t = param.getType();
		while (t instanceof KbTypedef) {
			t = ((KbTypedef)t).getAliasType();
		}
		if (t instanceof KbMapping || t instanceof KbStruct) {
			validator = "ref($" + name + ") eq 'HASH'";
		} else if (t instanceof KbList || t instanceof KbTuple) {
			validator = "ref($" + name + ") eq 'ARRAY'";
		} else if (t instanceof KbUnspecifiedObject) {
			validator = "defined $" + name;
		} else {
			validator = "!ref($" + name + ")";
		}
		ret.put("validator", validator);
		ret.put("perl_var", "$" + name);
		ret.put("baretype", getBareType(t));
		return ret;
	}
	
	private static String getBareType(KbType t) {
		if (t instanceof KbScalar) {
			return ((KbScalar)t).getSpecName();
		} else if (t instanceof KbList) {
			return "list";
		} else if (t instanceof KbMapping) {
			return "mapping";
		} else if (t instanceof KbTuple) {
			return "tuple";
		} else if (t instanceof KbStruct) {
			return "struct";
		} else if (t instanceof KbUnspecifiedObject) {
			return "UnspecifiedObject";
		} else {
			throw new IllegalStateException(t.getClass().getSimpleName());
		}
	}

	@Override
	public Map<String, Object> visit(final KbScalar scalar) {
		return null;
	}

	@Override
	public Map<String, Object> visit(
			final KbStruct struct,
			final List<Map<String, Object>> fields
		) {
		return null;
	}

	@Override
	public Map<String, Object> visit(final KbStructItem field, final Map<String, Object> type) {
		return null;
	}

	@Override
	public Map<String, Object> visit(
			final KbTuple tuple,
			final List<Map<String, Object>> elementTypes
		) {
		return null;
	}

	@Override
	public Map<String, Object> visit(final KbTypedef typedef, final KidlNode parent,
			final Map<String, Object> aliasType) {
		Map<String, Object> ret = new LinkedHashMap<String, Object>();
		ret.put("name", typedef.getName());
		ret.put("comment", typedef.getComment());
		ret.put("english", getTypeInEnglish(typedef.getAliasType()));
		return ret;
	}

	private static String getTypeInEnglish(KbType type) {
		Set<String> allKeys = new HashSet<String>();
		List<String> additional = new ArrayList<>();
		final LinkedList<NameAndTypeAlias> subQueue = new LinkedList<NameAndTypeAlias>();
		StringBuilder ret = new StringBuilder(
				Utils.getEnglishTypeDescr(type, subQueue, allKeys, additional)
		);
		if (additional.size() > 0)
			ret.append(":\n");
		for (String add : additional)
			ret.append(add).append("\n");
		return ret.toString();
	}
	
	@Override
	public Map<String, Object> visit(final KbUnspecifiedObject obj) {
		return null;
	}

}
