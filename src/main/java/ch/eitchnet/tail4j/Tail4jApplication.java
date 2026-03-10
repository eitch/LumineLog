package ch.eitchnet.tail4j;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class Tail4jApplication extends Application {
	@Override
	public void start(Stage stage) throws IOException {
		FXMLLoader loader = new FXMLLoader(getClass().getResource("view/main.fxml"));
		Parent root = loader.load();
		Scene scene = new Scene(root, 1124, 768);
		stage.setScene(scene);
		stage.setTitle("Tail4j Log Viewer");
		stage.show();
	}

	void main() {
		launch();
	}
}
