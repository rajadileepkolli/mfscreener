package com.example.mfscreener.service.impl;

import com.example.mfscreener.entities.MFScheme;
import com.example.mfscreener.entities.MFSchemeNav;
import com.example.mfscreener.entities.MFSchemeType;
import com.example.mfscreener.entities.TransactionRecord;
import com.example.mfscreener.exception.NavNotFoundException;
import com.example.mfscreener.exception.SchemeNotFoundException;
import com.example.mfscreener.model.*;
import com.example.mfscreener.repository.MFSchemeRepository;
import com.example.mfscreener.repository.MFSchemeTypeRepository;
import com.example.mfscreener.repository.TransactionRecordRepository;
import com.example.mfscreener.service.NavService;
import com.example.mfscreener.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Service
@Slf4j
@RequiredArgsConstructor
public class NavServiceImpl implements NavService {

  private final MFSchemeRepository mfSchemesRepository;
  private final MFSchemeTypeRepository mfSchemeTypeRepository;
  private final RestTemplate restTemplate;
  private final TransactionRecordRepository transactionRecordRepository;

  Function<NAVData, MFSchemeNav> navDataToMFSchemeNavFunction =
      navData -> {
        MFSchemeNav mfSchemeNav = new MFSchemeNav();
        mfSchemeNav.setNav(Double.parseDouble(navData.getNav()));
        mfSchemeNav.setNavDate(LocalDate.parse(navData.getDate(), Constants.DATE_FORMATTER));
        return mfSchemeNav;
      };

  @Override
  public Scheme getNav(Long schemeCode) {
    return mfSchemesRepository
        .findBySchemeIdAndNavDate(schemeCode, getAdjustedDate(LocalDate.now()))
        .map(this::convertToDTO)
        .orElseThrow(() -> new SchemeNotFoundException("Scheme Not Found"));
  }

  @Override
  public Scheme getNavOnDate(Long schemeCode, String inputDate) {
    LocalDate adjustedDate = getAdjustedDateForNAV(inputDate);
    return getNavByDate(schemeCode, adjustedDate);
  }

  @Override
  public void fetchSchemeDetails(Long schemeCode) {
    URI uri =
        UriComponentsBuilder.fromHttpUrl(Constants.MFAPI_WEBSITE_BASE_URL + schemeCode)
            .build()
            .toUri();

    ResponseEntity<NavResponse> navResponseResponseEntity =
        this.restTemplate.exchange(uri, HttpMethod.GET, null, NavResponse.class);
    if (navResponseResponseEntity.getStatusCode().is2xxSuccessful()) {
      NavResponse entityBody = navResponseResponseEntity.getBody();
      Assert.notNull(entityBody, () -> "Body Can't be Null");
      MFScheme mfScheme =
          mfSchemesRepository
              .findBySchemeId(schemeCode)
              .orElseThrow(
                  () ->
                      new SchemeNotFoundException(
                          "Fund with schemeCode " + schemeCode + " Not Found"));
      mergeList(entityBody, mfScheme);
    }
  }

  @Override
  public List<FundDetailDTO> fetchSchemes(String schemeName) {
    return this.mfSchemesRepository.findBySchemeNameIgnoringCaseLike("%" + schemeName + "%");
  }

  @Override
  public List<FundDetailDTO> fetchSchemesByFundName(String fundName) {
    return this.mfSchemesRepository.findByFundHouseIgnoringCaseLike("%" + fundName + "%");
  }

  private Scheme getSchemeDetails(Long schemeCode, LocalDate navDate) {
    fetchSchemeDetails(schemeCode);
    return this.mfSchemesRepository
        .findBySchemeIdAndNavDate(schemeCode, navDate)
        .map(this::convertToDTO)
        .orElseThrow(() -> new NavNotFoundException("Nav Not Found for given Date"));
  }

  private void mergeList(@NonNull NavResponse navResponse, MFScheme mfScheme) {
    List<NAVData> navList = navResponse.getData();

    List<MFSchemeNav> newNavs =
        navList.stream()
            .map(navDataToMFSchemeNavFunction)
            .filter(nav -> !mfScheme.getMfSchemeNavies().contains(nav))
            .toList();

    if (!newNavs.isEmpty()) {
      for (MFSchemeNav newSchemeNav : newNavs) {
        mfScheme.addSchemeNav(newSchemeNav);
      }
      final Meta meta = navResponse.getMeta();
      MFSchemeType mfschemeType =
          this.mfSchemeTypeRepository
              .findBySchemeCategoryAndSchemeType(meta.getSchemeCategory(), meta.getSchemeType())
              .orElseGet(
                  () -> {
                    MFSchemeType entity = new MFSchemeType();
                    entity.setSchemeType(meta.getSchemeType());
                    entity.setSchemeCategory(meta.getSchemeCategory());
                    return this.mfSchemeTypeRepository.save(entity);
                  });
      mfScheme.setFundHouse(meta.getFundHouse());
      mfschemeType.addMFScheme(mfScheme);
      this.mfSchemesRepository.save(mfScheme);
    }
  }

  private Scheme getNavByDate(Long schemeCode, LocalDate navDate) {
    return this.mfSchemesRepository
        .findBySchemeIdAndNavDate(schemeCode, navDate)
        .map(this::convertToDTO)
        .orElseGet(() -> getSchemeDetails(schemeCode, navDate));
  }

  private LocalDate getAdjustedDate(LocalDate adjustedDate) {
    if (adjustedDate.getDayOfWeek() == DayOfWeek.SATURDAY
        || adjustedDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
      adjustedDate = adjustedDate.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));
    }
    return adjustedDate;
  }

  private LocalDate getAdjustedDateForNAV(String inputDate) {
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(Constants.DATE_PATTERN_DD_MM_YYYY);
    LocalDate adjustedDate = LocalDate.parse(inputDate, formatter);
    return getAdjustedDate(adjustedDate);
  }

  private Scheme convertToDTO(MFScheme mfScheme) {
    Scheme scheme = new Scheme();
    scheme.setSchemeCode(String.valueOf(mfScheme.getSchemeId()));
    scheme.setSchemeName(mfScheme.getSchemeName());
    scheme.setPayout(mfScheme.getPayOut());
    scheme.setDate(String.valueOf(mfScheme.getMfSchemeNavies().get(0).getNavDate()));
    scheme.setNav(String.valueOf(mfScheme.getMfSchemeNavies().get(0).getNav()));
    return scheme;
  }

  @Override
  public String upload() throws IOException {
    File file = new File("C:\\Users\\rajakolli\\Desktop\\my-transactions.xls");

    List<TransactionRecord> transactionRecordList = new ArrayList<>();
    try (FileInputStream fileInputStream = new FileInputStream(file)) {
      // Create Workbook instance holding reference to .xlsx file
      HSSFWorkbook workbook = new HSSFWorkbook(fileInputStream);

      // Get first/desired sheet from the workbook
      HSSFSheet sheet = workbook.getSheetAt(0);

      // Iterate through each rows one by one
      for (int i = 1; i <= sheet.getLastRowNum(); i++) {

        Row row = sheet.getRow(i);

        TransactionRecord transactionRecord = new TransactionRecord();

        transactionRecord.setTransactionDate(
            LocalDate.from(row.getCell(0).getLocalDateTimeCellValue()));
        transactionRecord.setSchemeName(row.getCell(1).getStringCellValue());
        transactionRecord.setFolioNumber(getFolioNumber(row.getCell(2)));
        transactionRecord.setTransactionType(row.getCell(3).getStringCellValue());
        transactionRecord.setPrice(
            Double.parseDouble(String.valueOf(row.getCell(4).getNumericCellValue())));
        transactionRecord.setUnits(getUnits(row.getCell(5)));
        transactionRecordList.add(transactionRecord);
      }
    }

    transactionRecordRepository.saveAll(transactionRecordList);

    return "Completed Processing";
  }

  private double getUnits(Cell cell) {
    if (cell.getCellType().equals(CellType.STRING)) {
      return 0D;
    } else {
      return Double.parseDouble(String.valueOf(cell.getNumericCellValue()));
    }
  }

  private String getFolioNumber(Cell cell) {
    if (cell.getCellType().equals(CellType.NUMERIC)) {
      return String.valueOf(cell.getNumericCellValue());
    } else {
      return cell.getStringCellValue();
    }
  }
}
