package ch.eitchnet.tail4j.model;

import java.util.ArrayList;
import java.util.List;

public class HighlightGroup {
	private String name;
	private List<HighlightRule> rules = new ArrayList<>();

	public HighlightGroup(String name, List<HighlightRule> rules) {
		this.name = name;
		this.rules = rules != null ? rules : new ArrayList<>();
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<HighlightRule> getRules() {
		if (rules == null) {
			rules = new ArrayList<>();
		}
		return rules;
	}

	public void setRules(List<HighlightRule> rules) {
		this.rules = rules != null ? rules : new ArrayList<>();
	}
}
