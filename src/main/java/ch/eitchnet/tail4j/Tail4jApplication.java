package ch.eitchnet.tail4j;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class Tail4jApplication extends Application {
	private LogViewerController controller;

	@Override
	public void start(Stage stage) throws IOException {
		FXMLLoader fxmlLoader = new FXMLLoader(Tail4jApplication.class.getResource("log-viewer.fxml"));
		Scene scene = new Scene(fxmlLoader.load(), 900, 600);
		controller = fxmlLoader.getController();

		stage.setTitle("Tail4j - Log File Viewer");
		stage.setScene(scene);
		stage.setOnCloseRequest(event -> {
			if (controller != null) {
				controller.shutdown();
			}
		});
		stage.show();
	}

	public static void main(String[] args) {
		launch(args);
	}
}
