package ch.eitchnet.luminelog.model;

import java.util.ArrayList;
import java.util.List;

public class Config {
	private String lastOpenFile;
	private String lastGroup;
	private List<HighlightGroup> highlightGroups;

	public Config(String lastOpenFile, String lastGroup, List<HighlightGroup> highlightGroups) {
		this.lastOpenFile = lastOpenFile;
		this.lastGroup = lastGroup;
		this.highlightGroups = highlightGroups != null ? highlightGroups : new ArrayList<>();
	}

	public String getLastOpenFile() {
		return lastOpenFile;
	}

	public void setLastOpenFile(String lastOpenFile) {
		this.lastOpenFile = lastOpenFile;
	}

	public String getLastGroup() {
		return lastGroup;
	}

	public void setLastGroup(String lastGroup) {
		this.lastGroup = lastGroup;
	}

	public List<HighlightGroup> getHighlightGroups() {
		if (highlightGroups == null) {
			highlightGroups = new ArrayList<>();
		}
		return highlightGroups;
	}

	public void setHighlightGroups(List<HighlightGroup> highlightGroups) {
		this.highlightGroups = highlightGroups != null ? highlightGroups : new ArrayList<>();
	}
}
