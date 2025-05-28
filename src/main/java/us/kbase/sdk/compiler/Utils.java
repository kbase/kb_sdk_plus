package us.kbase.sdk.compiler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import us.kbase.kidl.KbList;
import us.kbase.kidl.KbMapping;
import us.kbase.kidl.KbScalar;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KbUnspecifiedObject;

public class Utils {
	
	/** The name of a type along with the alias of the type. */
	public static class NameAndTypeAlias {
		// TODO CODE all this stuff needs a refactor most likely - side effects etc.
		private final String name;
		private final KbType aliasType;

		/** Create the name and type holder.
		 * @param name the type's name.
		 * @param aliasType the alias for the type.
		 */
		public NameAndTypeAlias(final String name, final KbType aliasType) {
			this.name = name;
			this.aliasType = aliasType;
		}

		/** Get the type name.
		 * @return the name.
		 */
		public String getName() {
			return name;
		}

		/** Get the alias of the type.
		 * @return the type's alias.
		 */
		public KbType getAliasType() {
			return aliasType;
		}
	}
	
    public static String getEnglishTypeDescr(
            KbType type, LinkedList<NameAndTypeAlias> typeQueue, 
            Set<String> allKeys, List<String> additional) {
        return getEnglishTypeDescr(type, typeQueue, allKeys, additional, 0);
    }
    
    private static String getEnglishTypeDescr(
            KbType type, LinkedList<NameAndTypeAlias> typeQueue, 
            Set<String> allKeys, List<String> additional, int nested) {
        String descr = null;
        if (type instanceof KbScalar) {
            KbScalar sc = (KbScalar)type;
            descr = sc.getSpecName();
        } else if (type instanceof KbTypedef) {
            KbTypedef td = (KbTypedef)type;
            descr = td.getModule() + "." + td.getName();
            if (!allKeys.contains(td.getName())) {
                typeQueue.add(new NameAndTypeAlias(td.getName(), td.getAliasType()));
            }
        } else if (type instanceof KbList) {
            KbList ls = (KbList)type;
            descr = "reference to a list where each element is " + 
                    getEnglishTypeDescr(ls.getElementType(), typeQueue, allKeys, additional, nested);
        } else if (type instanceof KbMapping) {
            KbMapping mp = (KbMapping)type;
            descr = "reference to a hash where the key is " + 
                    getEnglishTypeDescr(mp.getKeyType(), typeQueue, allKeys, additional, nested) + 
                    " and the value is " + 
                    getEnglishTypeDescr(mp.getValueType(), typeQueue, allKeys, additional, nested);
        } else if (type instanceof KbTuple) {
            KbTuple tp = (KbTuple)type;
            int count = tp.getElementTypes().size();
            descr = "reference to a list containing " + count + " item" + (count == 1 ? "" : "s"); 
            if (additional != null) {
                for (int i = 0; i < count; i++) {
                    String tpName = tp.getElementNames().get(i);
                    String defName = "e_" + (i + 1);
                    KbType tpType = tp.getElementTypes().get(i);
                    List<String> nestedAdds = new ArrayList<String>();
                    String text = i + ":" + (tpName != null && !tpName.equals(defName) ? (" (" + tpName + ")") : "") + 
                            " " + getEnglishTypeDescr(tpType, typeQueue, allKeys, nestedAdds, nested + 1);
                    addNested(additional, nestedAdds, text, nested + 1);
                }
            }
        } else if (type instanceof KbStruct) {
            KbStruct st = (KbStruct)type;
            descr = "reference to a hash where the following keys are defined";
            if (additional != null) {
                for (KbStructItem item : st.getItems()) {
                    String itName = item.getName();
                    KbType itType = item.getItemType();
                    List<String> nestedAdds = new ArrayList<String>();
                    String text = itName + " has a value which is " + 
                            getEnglishTypeDescr(itType, typeQueue, allKeys, nestedAdds, nested + 1);
                    addNested(additional, nestedAdds, text, nested + 1);
                }
            }
        } else if (type instanceof KbUnspecifiedObject) {
            descr = "UnspecifiedObject, which can hold any non-null object";
        } else {
            descr = type.toString();
        }
        if (descr.length() > 0) {
            char firstLetter = descr.charAt(0);
            descr = "a" + (isVowel(firstLetter) ? "n" : "") + " " + descr;
        }
        return descr;
    }

    private static void addNested(List<String> additional,
            List<String> nestedAdds, String text, int nested) {
        if (nestedAdds.size() > 0)
            text += ":";
        additional.add(text);
        if (nestedAdds.size() > 0) {
            String nestedFlank = getNestedFlank(nested);
            for (String add : nestedAdds)
                additional.add(nestedFlank + add);
            additional.add("");
        }
    }
    
    private static String getNestedFlank(int nested) {
        char[] ret = new char[nested];
        Arrays.fill(ret, '\t');
        return new String(ret);
    }
    
    private static boolean isVowel(char c) {
        return "AEIOUaeiou".indexOf(c) != -1;
    }
    
    public static String removeStarsInComment(String comment) {
        int base = 0;
        BufferedReader br = new BufferedReader(new StringReader(comment));
        StringBuilder sb = new StringBuilder();
        try {
            for (int lineNum = 0;; lineNum++) {
                String l = br.readLine();
                if (l == null)
                    break;
                int starPos = l.indexOf('*');
                if (starPos >= 0 && l.substring(0, starPos).trim().length() == 0 &&
                        (l.length() == starPos + 1 || l.charAt(starPos + 1) != '*'))
                    l = l.substring(starPos + 1);
                if (lineNum == 1)
                    while (base < l.length() && l.charAt(base) == ' ')
                        base++;
                if (l.length() >= base && l.substring(0, base).trim().length() == 0)
                    l = l.substring(base);
                sb.append(l).append('\n');
            }
            br.close();
        } catch (IOException ex) {
            throw new IllegalStateException("Unexpected error", ex);
        }
        us.kbase.jkidl.Utils.trimWhitespaces(sb);
        return sb.toString();
    }
    
    public static List<String> parseCommentLines(String comment) {
        List<String> commentLines = new ArrayList<String>();
        if (comment != null && comment.trim().length() > 0) {
            StringTokenizer st = new StringTokenizer(comment, "\r\n");
            while (st.hasMoreTokens()) {
                commentLines.add(st.nextToken());
            }
            removeEmptyLinesOnSides(commentLines);
        }
        return commentLines;
    }
    
    private static void removeEmptyLinesOnSides(List<String> lines) {
        while (lines.size() > 0 && lines.get(0).trim().length() == 0)
            lines.remove(0);
        while (lines.size() > 0 && lines.get(lines.size() - 1).trim().length() == 0)
            lines.remove(lines.size() - 1);
    }

}
