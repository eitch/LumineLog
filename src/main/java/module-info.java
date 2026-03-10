module ch.eitchnet.tail4j {
	requires javafx.controls;
	requires javafx.fxml;
	requires java.prefs;
	requires org.slf4j;

	opens ch.eitchnet.tail4j to javafx.fxml;
	opens ch.eitchnet.tail4j.controller to javafx.fxml;
	exports ch.eitchnet.tail4j;
}
