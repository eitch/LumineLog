/*
 * Copyright (c) 2026 Robert von Burg <eitch@eitchnet.ch>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package ch.eitchnet.luminelog.model;

import java.util.ArrayList;
import java.util.List;

public class Config {
	private String lastOpenFile;
	private String lastGroup;
	private int fontSize;
	private List<HighlightGroup> highlightGroups;

	public Config(String lastOpenFile, String lastGroup, int fontSize, List<HighlightGroup> highlightGroups) {
		this.lastOpenFile = lastOpenFile;
		this.lastGroup = lastGroup;
		this.fontSize = fontSize;
		this.highlightGroups = highlightGroups != null ? highlightGroups : new ArrayList<>();
	}

	public int getFontSize() {
		return fontSize;
	}

	public void setFontSize(int fontSize) {
		this.fontSize = fontSize;
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
