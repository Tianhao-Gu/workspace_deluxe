package us.kbase.workspace.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class WorkspaceObjectData {
	
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final JsonNode data;
	private final ObjectInformation meta;
	private final Provenance prov;

	public WorkspaceObjectData(final JsonNode data,
			final ObjectInformation meta, final Provenance prov) {
		if (data == null || meta == null || prov == null) {
			throw new IllegalArgumentException(
					"data, prov and meta cannot be null");
		}
		this.data = data;
		this.meta = meta;
		this.prov = prov;
	}

	public JsonNode getDataAsJsonNode() {
		return data;
	}
	
	public Object getData() {
		try {
			return MAPPER.treeToValue(data, Object.class);
		} catch (JsonProcessingException jpe) {
			//this should never happen
			throw new RuntimeException("something's dun broke", jpe);
		}
	}

	public ObjectInformation getMeta() {
		return meta;
	}

	public Provenance getProvenance() {
		return prov;
	}

	@Override
	public String toString() {
		return "WorkspaceObjectData [data=" + data + ", meta=" + meta
				+ ", prov=" + prov + "]";
	}
}
