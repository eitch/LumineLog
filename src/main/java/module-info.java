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
