package com.example.bdget.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class StudentModelTest {

    @Test
    void testGettersAndSetters() {
        Student student = new Student();
        student.setId(1L);
        student.setName("Maria");

        assertEquals(1L, student.getId());
        assertEquals("Maria", student.getName());
    }
}
