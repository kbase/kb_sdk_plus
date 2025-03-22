package us.kbase.kidl;

import java.util.Map;

/**
 * Input or output part of KbFuncdef.
 * @author rsutormin
 */
public class KbParameter implements KidlNode {
	private String name;
	private String nameNotNullIfPossible;
	private KbType type;
	
	public KbParameter() {}
	
	public KbParameter(KbType type, String name) {
		this.name = name;
		this.nameNotNullIfPossible = name;
		this.type = type;
	}
	
	public KbParameter loadFromMap(Map<?,?> data, boolean isReturn, int paramNum) throws KidlParseException {
		name = Utils.propOrNull(data, "name"); // Utils.prop(data, "name");
		type = Utils.createTypeFromMap(Utils.propMap(data, "type"));
		nameNotNullIfPossible = name;
		if (nameNotNullIfPossible == null && !isReturn) {
			nameNotNullIfPossible = "arg" + paramNum;
		}
		return this;
	}

	public String getOriginalName() {
		return name;
	}

	public String getName() {
		return nameNotNullIfPossible;
	}

	public KbType getType() {
		return type;
	}
	
	@Override
	public <T> T accept(final KidlVisitor<T> visitor, final KidlNode parent) {
		return visitor.visit(this, type.accept(visitor, this));
	}
	

	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("KbParameter [name=");
		builder.append(name);
		builder.append(", nameNotNullIfPossible=");
		builder.append(nameNotNullIfPossible);
		builder.append(", type=");
		builder.append(type);
		builder.append("]");
		return builder.toString();
	}
}
