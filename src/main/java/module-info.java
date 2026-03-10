module ch.eitchnet.tail4j {
	requires javafx.controls;
	requires javafx.fxml;
	requires java.prefs;
	requires org.slf4j;
	requires com.google.gson;
	opens ch.eitchnet.tail4j to javafx.fxml;
	opens ch.eitchnet.tail4j.controller to javafx.fxml;
	opens ch.eitchnet.tail4j.model to com.google.gson;
	exports ch.eitchnet.tail4j;
}
