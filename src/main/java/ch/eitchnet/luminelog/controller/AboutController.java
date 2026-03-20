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
package ch.eitchnet.luminelog.controller;

import ch.eitchnet.luminelog.LumineLogApplication;
import ch.eitchnet.luminelog.util.DialogUtil;
import javafx.application.HostServices;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.InputStream;

public class AboutController {

	@FXML
	private ImageView logoImageView;
	@FXML
	private Label versionLabel;

	@FXML
	public void initialize() {
		InputStream logoImageStream = getClass().getResourceAsStream("/ch/eitchnet/luminelog/assets/LumineLog.png");
		if (logoImageStream == null) {
			DialogUtil.showError("Failed to load logo image");
		} else {
			Image image = new Image(logoImageStream);
			if (image.isError()) {
				DialogUtil.showError("Failed to load logo image: " + image.getException().getMessage());
			} else {
				logoImageView.setImage(image);
			}
		}

		// In a real app, version would be loaded from a properties file or manifest
		String version = getClass().getPackage().getImplementationVersion();
		if (version == null)
			version = "Development";
		versionLabel.setText("Version " + version);
	}

	@FXML
	private void handleOpenGithub() {
		String url = "https://github.com/eitch/LumineLog";
		HostServices hostServices = LumineLogApplication.getAppHostServices();
		if (hostServices != null) {
			hostServices.showDocument(url);
		} else {
			DialogUtil.showError("Failed to open GitHub link: HostServices not available");
		}
	}

	@FXML
	private void handleClose() {
		((Stage) logoImageView.getScene().getWindow()).close();
	}
}
