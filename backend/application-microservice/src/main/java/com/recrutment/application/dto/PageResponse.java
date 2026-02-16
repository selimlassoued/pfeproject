package com.recrutment.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> content;
    private int page;           // 0-based
    private int size;
    private long totalElements;
    private int totalPages;
}