package com.recrutment.application.converters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Converter
public class LanguageListConverter implements AttributeConverter<List<Map<String, String>>, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(List<Map<String, String>> list) {
        if (list == null || list.isEmpty()) return "[]";
        try { return mapper.writeValueAsString(list); } catch (Exception e) { return "[]"; }
    }

    @Override
    public List<Map<String, String>> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception e) { return new ArrayList<>(); }
    }
}
