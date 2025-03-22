package us.kbase.kidl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Class represents function definition in spec-file.
 */
public class KbFuncdef implements KbModuleDef {
	private String name;
	private String authentication;
	private String comment;
	private List<KbParameter> parameters;
	private List<KbParameter> returnType;
	private KbAnnotations annotations;
	private Map<?,?> data = null;
	
	public KbFuncdef() {}

	public KbFuncdef(final String name, final String comment)
			throws KidlParseException {
		this.name = name;
		this.comment = comment == null ? "" : comment;
		parameters = new ArrayList<KbParameter>();
		returnType = new ArrayList<KbParameter>();
		annotations = new KbAnnotations().loadFromComment(this.comment, this);
	}

	public KbFuncdef loadFromMap(Map<?,?> data, String defaultAuth) throws KidlParseException {
		name = Utils.prop(data, "name");
		authentication = Utils.prop(data, "authentication");  // defaultAuth was already involved on kidl stage
		comment = Utils.prop(data, "comment");
		parameters = loadParameters(Utils.propList(data, "parameters"), false);
		returnType = loadParameters(Utils.propList(data, "return_type"), true);
		annotations = new KbAnnotations();
		if (data.containsKey("annotations")) {
			annotations.loadFromMap(Utils.propMap(data, "annotations"));
		}
		this.data = data;
		return this;
	}
	
	private static List<KbParameter> loadParameters(List<?> inputList, boolean isReturn) throws KidlParseException {
		List<KbParameter> ret = new ArrayList<KbParameter>();
		for (Map<?,?> data : Utils.repareTypingMap(inputList)) {
			ret.add(new KbParameter().loadFromMap(data, isReturn, ret.size() + 1));
		}
		return Collections.unmodifiableList(ret);
	}
	
	public String getName() {
		return name;
	}
	
	public String getAuthentication() {
		return authentication;
	}

	public boolean isAuthenticationRequired() {
	    return KbAuthdef.REQUIRED.equals(authentication);
	}

	public boolean isAuthenticationOptional() {
	    return KbAuthdef.OPTIONAL.equals(authentication);
	}

	public void setAuthentication(String authentication) {
		this.authentication = authentication;
	}
	
	public String getComment() {
		return comment;
	}
	
	public List<KbParameter> getParameters() {
		return parameters;
	}
	
	public List<KbParameter> getReturnType() {
		return returnType;
	}
	
	public Map<?, ?> getData() {
		return data;
	}
	

	@Override
	public KbAnnotations getAnnotations() {
		return annotations;
	}
	
	@Override
	public <T> T accept(final KidlVisitor<T> visitor, final KidlNode parent) {
		final List<T> params = new LinkedList<T>();
		final List<T> returns = new LinkedList<T>();
		for (final KbParameter p: parameters) {
			params.add(p.accept(visitor, this));
		}
		for (final KbParameter p: returnType) {
			returns.add(p.accept(visitor, this));
		}
		return visitor.visit(this, params, returns);
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("KbFuncdef [name=");
		builder.append(name);
		builder.append(", authentication=");
		builder.append(authentication);
		builder.append(", comment=");
		builder.append(comment);
		builder.append(", parameters=");
		builder.append(parameters);
		builder.append(", returnType=");
		builder.append(returnType);
		builder.append(", annotations=");
		builder.append(annotations);
		builder.append(", data=");
		builder.append(data);
		builder.append("]");
		return builder.toString();
	}
}
