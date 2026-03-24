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
	private List<OpenFileInfo> openFiles;
	private String lastGroup;
	private int fontSize;
	private List<HighlightGroup> highlightGroups;

	public Config(List<OpenFileInfo> openFiles, String lastGroup, int fontSize, List<HighlightGroup> highlightGroups) {
		this.openFiles = openFiles != null ? openFiles : new ArrayList<>();
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

	public List<OpenFileInfo> getOpenFiles() {
		if (openFiles == null) {
			openFiles = new ArrayList<>();
		}
		return openFiles;
	}

	public void setOpenFiles(List<OpenFileInfo> openFiles) {
		this.openFiles = openFiles != null ? openFiles : new ArrayList<>();
	}

	public String getLastOpenFile() {
		if (openFiles == null || openFiles.isEmpty())
			return null;
		return openFiles.getLast().filePath();
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
