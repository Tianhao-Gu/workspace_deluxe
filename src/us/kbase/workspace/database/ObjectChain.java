package us.kbase.workspace.database;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class ObjectChain {
	
	private ObjectIdentifier head;
	private List<ObjectIdentifier> chain;
	
	public ObjectChain(final ObjectIdentifier head,
			final List<ObjectIdentifier> chain) {
		this.head = head;
		this.chain = Collections.unmodifiableList(
				new LinkedList<ObjectIdentifier>());
	}

	public ObjectIdentifier getHead() {
		return head;
	}

	public List<ObjectIdentifier> getChain() {
		return chain;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((chain == null) ? 0 : chain.hashCode());
		result = prime * result + ((head == null) ? 0 : head.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ObjectChain other = (ObjectChain) obj;
		if (chain == null) {
			if (other.chain != null)
				return false;
		} else if (!chain.equals(other.chain))
			return false;
		if (head == null) {
			if (other.head != null)
				return false;
		} else if (!head.equals(other.head))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return "ObjectChain [head=" + head + ", chain=" + chain + "]";
	}

}
