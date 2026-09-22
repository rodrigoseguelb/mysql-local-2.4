package com.example.bdget.service;

import com.example.bdget.model.Student;

import java.util.List;
import java.util.Optional;

/**
 * Contrato del servicio: define QUÉ operaciones existen.
 * La implementación concreta está en StudentServiceImpl.
 */
public interface StudentService {
    List<Student> getAllStudents();
    Optional<Student> getStudentById(Long id);
    Student createStudent(Student student);
    Student updateStudent(Long id, Student student);
    void deleteStudent(Long id);
}
