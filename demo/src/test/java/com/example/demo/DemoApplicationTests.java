package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.model.AppUser;
import com.example.demo.repo.appUserRepo;
import com.example.demo.repo.categoriaRepo;
import com.example.demo.repo.comentarioRepo;
import com.example.demo.repo.imagenRepo;
import com.example.demo.repo.productoRepo;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class DemoApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private productoRepo productoRepo;

	@Autowired
	private categoriaRepo categoriaRepo;

	@Autowired
	private comentarioRepo comentarioRepo;

	@Autowired
	private imagenRepo imagenRepo;

	@Autowired
	private appUserRepo appUserRepo;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void cleanUp() {
		categoriaRepo.deleteAll();
		comentarioRepo.deleteAll();
		imagenRepo.deleteAll();
		productoRepo.deleteAll();
		appUserRepo.deleteAll();
	}

	private String loginAndGetToken(String username, String password) throws Exception {
		String body = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
		MvcResult res = mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.accessToken").isString())
				.andReturn();
		return objectMapper.readTree(res.getResponse().getContentAsString()).get("accessToken").asText();
	}

	@Test
	void openApiPublicaEsquemaBearerJwtYSwaggerUiCarga() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());

		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
				.andExpect(jsonPath("$.paths['/imagenes/producto/{productId}'].get").exists());
	}

	@Test
	void todosLosGetRespondenSinToken() throws Exception {
		mockMvc.perform(get("/productos")).andExpect(status().isOk()).andExpect(content().json("[]"));
		mockMvc.perform(get("/producto/inexistente")).andExpect(status().isNotFound());

		mockMvc.perform(get("/categorias")).andExpect(status().isOk()).andExpect(content().json("[]"));
		mockMvc.perform(get("/categoria/inexistente")).andExpect(status().isNotFound());
		mockMvc.perform(get("/categoria/producto/inexistente")).andExpect(status().isNotFound());

		mockMvc.perform(get("/comentarios")).andExpect(status().isOk()).andExpect(content().json("[]"));
		mockMvc.perform(get("/comentario/inexistente")).andExpect(status().isNotFound());
		mockMvc.perform(get("/comentarios/inexistente")).andExpect(status().isNotFound());

		mockMvc.perform(get("/imagenes")).andExpect(status().isOk()).andExpect(content().json("[]"));
		mockMvc.perform(get("/imagen/inexistente")).andExpect(status().isNotFound());
		mockMvc.perform(get("/imagenes/producto/inexistente")).andExpect(status().isNotFound());
	}

	@Test
	void registroSiempreCreaUserYLoginValidaCredenciales() throws Exception {
		String registerBody = """
				{
				  "username": "estudiante",
				  "password": "clave-local-segura-123"
				}
				""";

		mockMvc.perform(post("/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content(registerBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value("estudiante"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		// Usuario duplicado -> 409 Conflict
		mockMvc.perform(post("/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content(registerBody))
				.andExpect(status().isConflict());

		// Login válido -> 200 OK + Bearer JWT
		String token = loginAndGetToken("estudiante", "clave-local-segura-123");
		assertThat(token).contains(".");

		// Login inválido -> 401 Unauthorized
		mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"estudiante\",\"password\":\"incorrecta\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void autorizacionPorRolesValidacionDtoYPatch() throws Exception {
		// Registrar cuenta USER vía endpoint público
		mockMvc.perform(post("/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user1\",\"password\":\"password-segura-123\"}"))
				.andExpect(status().isCreated());
		String userToken = loginAndGetToken("user1", "password-segura-123");

		// Crear cuenta ADMIN en base de datos
		appUserRepo.save(new AppUser("admin1", passwordEncoder.encode("admin-segura-123"), "ADMIN"));
		String adminToken = loginAndGetToken("admin1", "admin-segura-123");

		String productoValido = """
				{
				  "nombre": "Proteína de fresa",
				  "descripcion": "2.2 kg",
				  "lote": "123456",
				  "precioUnitario": 1500.5,
				  "fechaCaducidad": "2026-05-04"
				}
				""";

		// Escritura protegida sin token -> 401 Unauthorized
		mockMvc.perform(post("/producto")
				.contentType(MediaType.APPLICATION_JSON)
				.content(productoValido))
				.andExpect(status().isUnauthorized());

		// Token USER intentando crear producto o categoría -> 403 Forbidden
		mockMvc.perform(post("/producto")
				.header("Authorization", "Bearer " + userToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(productoValido))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/categoria")
				.header("Authorization", "Bearer " + userToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombre\":\"Suplementos\"}"))
				.andExpect(status().isForbidden());

		// DTO inválido -> 400 Bad Request
		mockMvc.perform(post("/producto")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombre\":\"\",\"descripcion\":\"\",\"lote\":\"\",\"precioUnitario\":-5}"))
				.andExpect(status().isBadRequest());

		// ADMIN crea producto -> 201 Created + Location
		MvcResult prodRes = mockMvc.perform(post("/producto")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(productoValido))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.nombre").value("Proteína de fresa"))
				.andReturn();
		String productId = objectMapper.readTree(prodRes.getResponse().getContentAsString()).get("id").asText();

		// PATCH vacío -> 400 Bad Request
		mockMvc.perform(patch("/producto/{id}", productId)
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest());

		// PATCH parcial -> modifica solo 'nombre' y conserva demás campos
		mockMvc.perform(patch("/producto/{id}", productId)
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombre\":\"Nuevo nombre\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nombre").value("Nuevo nombre"))
				.andExpect(jsonPath("$.descripcion").value("2.2 kg"))
				.andExpect(jsonPath("$.lote").value("123456"))
				.andExpect(jsonPath("$.precioUnitario").value(1500.5));

		// PUT en ID inexistente -> 404 Not Found
		mockMvc.perform(put("/producto/{id}", "no-existe")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(productoValido))
				.andExpect(status().isNotFound());

		// USER puede crear comentario e imagen asociados al producto -> 201 Created
		String comentarioValido = """
				{
				  "username": "user1",
				  "comentario_corto": "Excelente",
				  "estrellas": 5,
				  "comentario_largo": "Muy buena proteína"
				}
				""";
		MvcResult comRes = mockMvc.perform(post("/comentario/{productId}", productId)
				.header("Authorization", "Bearer " + userToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(comentarioValido))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andReturn();
		String commentId = objectMapper.readTree(comRes.getResponse().getContentAsString()).get("id").asText();

		String imagenValida = """
				{
				  "url": "https://example.com/foto.jpg",
				  "prioridad": 1,
				  "tamanio": "grande",
				  "responsiva": true
				}
				""";
		MvcResult imgRes = mockMvc.perform(post("/imagen/{productId}", productId)
				.header("Authorization", "Bearer " + userToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(imagenValida))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andReturn();
		String imageId = objectMapper.readTree(imgRes.getResponse().getContentAsString()).get("id").asText();

		// GET públicos devuelven comentarios e imágenes del producto sin token
		mockMvc.perform(get("/comentarios/{productId}", productId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		mockMvc.perform(get("/imagenes/producto/{productId}", productId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		// ADMIN crea categoría y asocia producto
		MvcResult catRes = mockMvc.perform(post("/categoria")
				.header("Authorization", "Bearer " + adminToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"nombre\":\"Suplementos\",\"descripcion\":\"Fitness\",\"tipo\":\"Principal\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		String catId = objectMapper.readTree(catRes.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(post("/addProductoCategoria/{productId}/{categoriaId}", productId, catId)
				.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(catId));

		mockMvc.perform(get("/categoria/producto/{productId}", productId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1));

		// DELETE con rol adecuado -> 204 No Content
		mockMvc.perform(delete("/comentario/{id}", commentId)
				.header("Authorization", "Bearer " + userToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(delete("/imagen/{id}", imageId)
				.header("Authorization", "Bearer " + userToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(delete("/categoria/{id}", catId)
				.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(delete("/producto/{id}", productId)
				.header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isNoContent());
	}
}
