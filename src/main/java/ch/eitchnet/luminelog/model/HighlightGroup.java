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

public class HighlightGroup {
	private String name;
	private List<HighlightRule> rules;

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
