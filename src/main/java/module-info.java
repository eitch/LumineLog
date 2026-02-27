module ch.eitchnet.tail4j {
	requires javafx.controls;
	requires javafx.fxml;

	opens ch.eitchnet.tail4j to javafx.fxml;
	exports ch.eitchnet.tail4j;
}