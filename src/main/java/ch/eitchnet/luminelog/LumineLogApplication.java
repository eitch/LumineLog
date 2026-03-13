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
package ch.eitchnet.luminelog;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class LumineLogApplication extends Application {
	private static HostServices hostServices;

	public static HostServices getAppHostServices() {
		return hostServices;
	}

	@Override
	public void start(Stage stage) throws IOException {
		hostServices = getHostServices();
		FXMLLoader loader = new FXMLLoader(getClass().getResource("view/main.fxml"));
		Parent root = loader.load();
		Scene scene = new Scene(root, 1200, 800);
		stage.setScene(scene);
		stage.setTitle("LumineLog");
		stage.show();
	}

	void main() {
		launch();
	}
}
