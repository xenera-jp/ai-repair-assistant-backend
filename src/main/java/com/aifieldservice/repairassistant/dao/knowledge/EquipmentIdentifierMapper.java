package com.aifieldservice.repairassistant.dao.knowledge;

import java.util.List;

/** Live identifiers from imported manuals and repair cases. */
public interface EquipmentIdentifierMapper {
    record Row(String model, String errorCodesJson) {}
    List<Row> listIdentifiers();
}
