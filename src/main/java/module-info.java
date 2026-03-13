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
module ch.eitchnet.luminelog {
	requires javafx.controls;
	requires javafx.fxml;
	requires java.prefs;
	requires org.slf4j;
	requires com.google.gson;
	opens ch.eitchnet.luminelog to javafx.fxml;
	opens ch.eitchnet.luminelog.controller to javafx.fxml;
	opens ch.eitchnet.luminelog.model to com.google.gson;
	exports ch.eitchnet.luminelog;
}
